// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.http.v2;

import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.model.NullConfigModelRegistry;
import com.yahoo.config.model.application.provider.FilesApplicationPackage;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.TenantName;
import com.yahoo.config.provision.Zone;
import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.jdisc.Response;
import com.yahoo.vespa.config.server.ApplicationRepository;
import com.yahoo.vespa.config.server.TestComponentRegistry;
import com.yahoo.vespa.config.server.application.OrchestratorMock;
import com.yahoo.vespa.config.server.host.HostRegistry;
import com.yahoo.vespa.config.server.http.HandlerTest;
import com.yahoo.vespa.config.server.http.HttpErrorResponse;
import com.yahoo.vespa.config.server.http.SessionHandlerTest;
import com.yahoo.vespa.config.server.modelfactory.ModelFactoryRegistry;
import com.yahoo.vespa.config.server.session.MockSessionZKClient;
import com.yahoo.vespa.config.server.session.RemoteSession;
import com.yahoo.vespa.config.server.tenant.Tenant;
import com.yahoo.vespa.config.server.tenant.TenantRepository;
import com.yahoo.vespa.model.VespaModelFactory;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.time.Clock;
import java.util.Collections;

/**
 * @author hmusum
 */
// TODO: Try to move testing to ApplicationRepositoryTest and avoid all the low-level setup code here
public class HostHandlerTest {

    private static final String urlPrefix = "http://myhost:14000/application/v2/host/";
    private static final File testApp = new File("src/test/apps/app");

    private HostHandler handler;
    private final static TenantName mytenant = TenantName.from("mytenant");
    private final static String hostname = "testhost";
    private final static Zone zone = Zone.defaultZone();
    private TenantRepository tenantRepository;

    static void addMockApplication(Tenant tenant, ApplicationId applicationId, long sessionId) {
        tenant.getApplicationRepo().createApplication(applicationId);
        tenant.getApplicationRepo().createPutTransaction(applicationId, sessionId).commit();
        ApplicationPackage app = FilesApplicationPackage.fromFile(testApp);
        tenant.getSessionRepository().addLocalSession(new SessionHandlerTest.MockLocalSession(sessionId, app, applicationId));
        TestComponentRegistry componentRegistry = new TestComponentRegistry.Builder()
                .modelFactoryRegistry(new ModelFactoryRegistry(Collections.singletonList(new VespaModelFactory(new NullConfigModelRegistry()))))
                .build();
        tenant.getSessionRepository().addRemoteSession(new RemoteSession(tenant.getName(), sessionId, componentRegistry, new MockSessionZKClient(app)));
    }

    @Before
    public void setup() {
        final HostRegistry<TenantName> hostRegistry = new HostRegistry<>();
        hostRegistry.update(mytenant, Collections.singletonList(hostname));
        TestComponentRegistry componentRegistry = new TestComponentRegistry.Builder()
                .zone(zone)
                .build();
        tenantRepository = new TenantRepository(componentRegistry, false);
        tenantRepository.addTenant(mytenant);
        Tenant tenant = tenantRepository.getTenant(mytenant);
        HostRegistry<ApplicationId> applicationHostRegistry = tenant.getApplicationRepo().getApplicationHostRegistry();
        applicationHostRegistry.update(ApplicationId.from(mytenant, ApplicationName.defaultName(), InstanceName.defaultName()), Collections.singletonList(hostname));
        ApplicationRepository applicationRepository = new ApplicationRepository(tenantRepository,
                                                                                new SessionHandlerTest.MockProvisioner(),
                                                                                new OrchestratorMock(),
                                                                                Clock.systemUTC());
        handler = new HostHandler(HostHandler.testOnlyContext(), applicationRepository);
    }

    @Test
    public void require_correct_tenant_and_application_for_hostname() throws Exception {
        long sessionId = 1;
        ApplicationId id = ApplicationId.from(mytenant, ApplicationName.defaultName(), InstanceName.defaultName());
        addMockApplication(tenantRepository.getTenant(mytenant), id, sessionId);
        assertApplicationForHost(hostname, mytenant, id, zone);
    }

    @Test
    public void require_that_handler_gives_error_for_unknown_hostname() throws Exception {
        long sessionId = 1;
        addMockApplication(tenantRepository.getTenant(mytenant), ApplicationId.defaultId(), sessionId);
        final String hostname = "unknown";
        assertErrorForHost(hostname,
                Response.Status.NOT_FOUND,
                HttpErrorResponse.errorCodes.NOT_FOUND,
                "{\"error-code\":\"NOT_FOUND\",\"message\":\"Could not find any application using host '" + hostname + "'\"}");
    }

    @Test
    public void require_that_only_get_method_is_allowed() throws IOException {
        assertNotAllowed(com.yahoo.jdisc.http.HttpRequest.Method.PUT);
        assertNotAllowed(com.yahoo.jdisc.http.HttpRequest.Method.POST);
        assertNotAllowed(com.yahoo.jdisc.http.HttpRequest.Method.DELETE);
    }

    private void assertNotAllowed(com.yahoo.jdisc.http.HttpRequest.Method method) throws IOException {
        String url = urlPrefix + hostname;
        deleteAndAssertResponse(url, Response.Status.METHOD_NOT_ALLOWED,
                HttpErrorResponse.errorCodes.METHOD_NOT_ALLOWED,
                "{\"error-code\":\"METHOD_NOT_ALLOWED\",\"message\":\"Method '" + method + "' is not supported\"}",
                method);
    }

    private void assertApplicationForHost(String hostname, TenantName expectedTenantName, ApplicationId expectedApplicationId, Zone zone) throws IOException {
        String url = urlPrefix + hostname;
        HttpResponse response = handler.handle(HttpRequest.createTestRequest(url, com.yahoo.jdisc.http.HttpRequest.Method.GET));
        HandlerTest.assertHttpStatusCodeAndMessage(response, Response.Status.OK,
                "{\"tenant\":\"" + expectedTenantName.value() + "\"," +
                        "\"application\":\"" + expectedApplicationId.application().value() + "\"," +
                        "\"environment\":\"" + zone.environment().value() + "\"," +
                        "\"region\":\"" + zone.region().value() + "\"," +
                        "\"instance\":\"" + expectedApplicationId.instance().value() + "\"}"
        );
    }

    private void assertErrorForHost(String hostname, int expectedStatus, HttpErrorResponse.errorCodes errorCode, String expectedResponse) throws IOException {
        String url = urlPrefix + hostname;
        HttpResponse response = handler.handle(HttpRequest.createTestRequest(url, com.yahoo.jdisc.http.HttpRequest.Method.GET));
        HandlerTest.assertHttpStatusCodeErrorCodeAndMessage(response, expectedStatus, errorCode, expectedResponse);
    }

    private void deleteAndAssertResponse(String url, int expectedStatus, HttpErrorResponse.errorCodes errorCode, String expectedResponse, com.yahoo.jdisc.http.HttpRequest.Method method) throws IOException {
        HttpResponse response = handler.handle(HttpRequest.createTestRequest(url, method));
        HandlerTest.assertHttpStatusCodeErrorCodeAndMessage(response, expectedStatus, errorCode, expectedResponse);
    }
}
