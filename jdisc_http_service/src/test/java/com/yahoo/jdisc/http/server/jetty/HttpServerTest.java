// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.jdisc.http.server.jetty;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.yahoo.container.logging.AccessLog;
import com.yahoo.container.logging.AccessLogEntry;
import com.yahoo.jdisc.Metric;
import com.yahoo.jdisc.References;
import com.yahoo.jdisc.Request;
import com.yahoo.jdisc.Response;
import com.yahoo.jdisc.application.BindingSetSelector;
import com.yahoo.jdisc.application.MetricConsumer;
import com.yahoo.jdisc.handler.AbstractRequestHandler;
import com.yahoo.jdisc.handler.CompletionHandler;
import com.yahoo.jdisc.handler.ContentChannel;
import com.yahoo.jdisc.handler.RequestHandler;
import com.yahoo.jdisc.handler.ResponseDispatch;
import com.yahoo.jdisc.handler.ResponseHandler;
import com.yahoo.jdisc.http.ConnectorConfig;
import com.yahoo.jdisc.http.ConnectorConfig.Throttling;
import com.yahoo.jdisc.http.Cookie;
import com.yahoo.jdisc.http.HttpRequest;
import com.yahoo.jdisc.http.HttpResponse;
import com.yahoo.jdisc.http.ServerConfig;
import com.yahoo.jdisc.http.server.jetty.JettyHttpServer.Metrics;
import com.yahoo.jdisc.http.server.jetty.TestDrivers.TlsClientAuth;
import com.yahoo.jdisc.service.BindingSetNotFoundException;
import com.yahoo.security.KeyUtils;
import com.yahoo.security.Pkcs10Csr;
import com.yahoo.security.Pkcs10CsrBuilder;
import com.yahoo.security.SslContextBuilder;
import com.yahoo.security.X509CertificateBuilder;
import com.yahoo.security.X509CertificateUtils;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.FormBodyPart;
import org.apache.http.entity.mime.content.StringBody;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.ProxyProtocolClientConnectionFactory.V1;
import org.eclipse.jetty.client.ProxyProtocolClientConnectionFactory.V2;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.security.auth.x500.X500Principal;
import java.io.IOException;
import java.math.BigInteger;
import java.net.BindException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static com.yahoo.jdisc.Response.Status.GATEWAY_TIMEOUT;
import static com.yahoo.jdisc.Response.Status.INTERNAL_SERVER_ERROR;
import static com.yahoo.jdisc.Response.Status.NOT_FOUND;
import static com.yahoo.jdisc.Response.Status.OK;
import static com.yahoo.jdisc.Response.Status.REQUEST_URI_TOO_LONG;
import static com.yahoo.jdisc.Response.Status.UNAUTHORIZED;
import static com.yahoo.jdisc.Response.Status.UNSUPPORTED_MEDIA_TYPE;
import static com.yahoo.jdisc.http.HttpHeaders.Names.CONNECTION;
import static com.yahoo.jdisc.http.HttpHeaders.Names.CONTENT_TYPE;
import static com.yahoo.jdisc.http.HttpHeaders.Names.COOKIE;
import static com.yahoo.jdisc.http.HttpHeaders.Names.X_DISABLE_CHUNKING;
import static com.yahoo.jdisc.http.HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED;
import static com.yahoo.jdisc.http.HttpHeaders.Values.CLOSE;
import static com.yahoo.jdisc.http.server.jetty.SimpleHttpClient.ResponseValidator;
import static com.yahoo.security.KeyAlgorithm.RSA;
import static com.yahoo.security.SignatureAlgorithm.SHA256_WITH_RSA;
import static org.cthul.matchers.CthulMatchers.containsPattern;
import static org.cthul.matchers.CthulMatchers.matchesPattern;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Oyvind Bakksjo
 * @author Simon Thoresen Hult
 * @author bjorncs
 */
public class HttpServerTest {

    private static final Logger log = Logger.getLogger(HttpServerTest.class.getName());

    @Rule
    public TemporaryFolder tmpFolder = new TemporaryFolder();

    @Test
    public void requireThatServerCanListenToRandomPort() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(mockRequestHandler());
        assertThat(driver.server().getListenPort(), is(not(0)));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatServerCanNotListenToBoundPort() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(mockRequestHandler());
        try {
            TestDrivers.newConfiguredInstance(
                    mockRequestHandler(),
                    new ServerConfig.Builder(),
                    new ConnectorConfig.Builder()
                            .listenPort(driver.server().getListenPort())
            );
        } catch (final Throwable t) {
            assertThat(t.getCause(), instanceOf(BindException.class));
        }
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatBindingSetNotFoundReturns404() throws Exception {
        final TestDriver driver = TestDrivers.newConfiguredInstance(
                mockRequestHandler(),
                new ServerConfig.Builder()
                        .developerMode(true),
                new ConnectorConfig.Builder(),
                newBindingSetSelector("unknown"));
        driver.client().get("/status.html")
              .expectStatusCode(is(NOT_FOUND))
              .expectContent(containsPattern(Pattern.compile(
                      Pattern.quote(BindingSetNotFoundException.class.getName()) +
                      ": No binding set named &apos;unknown&apos;\\.\n\tat .+",
                      Pattern.DOTALL | Pattern.MULTILINE)));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatTooLongInitLineReturns414() throws Exception {
        final TestDriver driver = TestDrivers.newConfiguredInstance(
                mockRequestHandler(),
                new ServerConfig.Builder(),
                new ConnectorConfig.Builder()
                        .requestHeaderSize(1));
        driver.client().get("/status.html")
              .expectStatusCode(is(REQUEST_URI_TOO_LONG));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatAccessLogIsCalledForRequestRejectedByJetty() throws Exception {
        AccessLogMock accessLogMock = new AccessLogMock();
        final TestDriver driver = TestDrivers.newConfiguredInstance(
                mockRequestHandler(),
                new ServerConfig.Builder(),
                new ConnectorConfig.Builder().requestHeaderSize(1),
                binder -> binder.bind(AccessLog.class).toInstance(accessLogMock));
        driver.client().get("/status.html")
                .expectStatusCode(is(REQUEST_URI_TOO_LONG));
        assertThat(driver.close(), is(true));

        assertThat(accessLogMock.logEntries.size(), equalTo(1));
        AccessLogEntry accessLogEntry = accessLogMock.logEntries.get(0);
        assertThat(accessLogEntry.getStatusCode(), equalTo(414));
    }

    private static class AccessLogMock extends AccessLog {

        final List<AccessLogEntry> logEntries = new CopyOnWriteArrayList<>();

        AccessLogMock() { super(null); }

        @Override
        public void log(AccessLogEntry accessLogEntry) {
            logEntries.add(accessLogEntry);
        }
    }

    @Test
    public void requireThatServerCanEcho() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new EchoRequestHandler());
        driver.client().get("/status.html")
              .expectStatusCode(is(OK));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatServerCanEchoCompressed() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new EchoRequestHandler());
        SimpleHttpClient client = driver.newClient(true);
        client.get("/status.html")
                .expectStatusCode(is(OK));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatServerCanHandleMultipleRequests() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new EchoRequestHandler());
        driver.client().get("/status.html")
              .expectStatusCode(is(OK));
        driver.client().get("/status.html")
              .expectStatusCode(is(OK));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatFormPostWorks() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new ParameterPrinterRequestHandler());
        final String requestContent = generateContent('a', 30);
        final ResponseValidator response =
                driver.client().newPost("/status.html")
                      .addHeader(CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED)
                      .setContent(requestContent)
                      .execute();
        response.expectStatusCode(is(OK))
                .expectContent(startsWith('{' + requestContent + "=[]}"));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatFormPostDoesNotRemoveContentByDefault() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new ParameterPrinterRequestHandler());
        final ResponseValidator response =
                driver.client().newPost("/status.html")
                        .addHeader(CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED)
                        .setContent("foo=bar")
                        .execute();
        response.expectStatusCode(is(OK))
                .expectContent(is("{foo=[bar]}foo=bar"));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatFormPostKeepsContentWhenConfiguredTo() throws Exception {
        final TestDriver driver = newDriverWithFormPostContentRemoved(new ParameterPrinterRequestHandler(), false);
        final ResponseValidator response =
                driver.client().newPost("/status.html")
                        .addHeader(CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED)
                        .setContent("foo=bar")
                        .execute();
        response.expectStatusCode(is(OK))
                .expectContent(is("{foo=[bar]}foo=bar"));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatFormPostRemovesContentWhenConfiguredTo() throws Exception {
        final TestDriver driver = newDriverWithFormPostContentRemoved(new ParameterPrinterRequestHandler(), true);
        final ResponseValidator response =
                driver.client().newPost("/status.html")
                        .addHeader(CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED)
                        .setContent("foo=bar")
                        .execute();
        response.expectStatusCode(is(OK))
                .expectContent(is("{foo=[bar]}"));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatFormPostWithCharsetSpecifiedWorks() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new ParameterPrinterRequestHandler());
        final String requestContent = generateContent('a', 30);
        final ResponseValidator response =
                driver.client().newPost("/status.html")
                      .addHeader(X_DISABLE_CHUNKING, "true")
                      .addHeader(CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED + ";charset=UTF-8")
                      .setContent(requestContent)
                      .execute();
        response.expectStatusCode(is(OK))
                .expectContent(startsWith('{' + requestContent + "=[]}"));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatEmptyFormPostWorks() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new ParameterPrinterRequestHandler());
        final ResponseValidator response =
                driver.client().newPost("/status.html")
                      .addHeader(CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED)
                      .execute();
        response.expectStatusCode(is(OK))
                .expectContent(is("{}"));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatFormParametersAreParsed() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new ParameterPrinterRequestHandler());
        final ResponseValidator response =
                driver.client().newPost("/status.html")
                      .addHeader(CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED)
                      .setContent("a=b&c=d")
                      .execute();
        response.expectStatusCode(is(OK))
                .expectContent(startsWith("{a=[b], c=[d]}"));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatUriParametersAreParsed() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new ParameterPrinterRequestHandler());
        final ResponseValidator response =
                driver.client().newPost("/status.html?a=b&c=d")
                      .addHeader(CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED)
                      .execute();
        response.expectStatusCode(is(OK))
                .expectContent(is("{a=[b], c=[d]}"));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatFormAndUriParametersAreMerged() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new ParameterPrinterRequestHandler());
        final ResponseValidator response =
                driver.client().newPost("/status.html?a=b&c=d1")
                      .addHeader(CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED)
                      .setContent("c=d2&e=f")
                      .execute();
        response.expectStatusCode(is(OK))
                .expectContent(startsWith("{a=[b], c=[d1, d2], e=[f]}"));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatFormCharsetIsHonored() throws Exception {
        final TestDriver driver = newDriverWithFormPostContentRemoved(new ParameterPrinterRequestHandler(), true);
        final ResponseValidator response =
                driver.client().newPost("/status.html")
                        .addHeader(CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED + ";charset=ISO-8859-1")
                        .setBinaryContent(new byte[]{66, (byte) 230, 114, 61, 98, 108, (byte) 229})
                        .execute();
        response.expectStatusCode(is(OK))
                .expectContent(is("{B\u00e6r=[bl\u00e5]}"));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatUnknownFormCharsetIsTreatedAsBadRequest() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new ParameterPrinterRequestHandler());
        final ResponseValidator response =
                driver.client().newPost("/status.html")
                        .addHeader(CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED + ";charset=FLARBA-GARBA-7")
                        .setContent("a=b")
                        .execute();
        response.expectStatusCode(is(UNSUPPORTED_MEDIA_TYPE));
        assertThat(driver.close(), is(true));
    }
    
    @Test
    public void requireThatFormPostWithPercentEncodedContentIsDecoded() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new ParameterPrinterRequestHandler());
        final ResponseValidator response =
                driver.client().newPost("/status.html")
                        .addHeader(CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED)
                        .setContent("%20%3D%C3%98=%22%25+")
                        .execute();
        response.expectStatusCode(is(OK))
                .expectContent(startsWith("{ =\u00d8=[\"% ]}"));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatFormPostWithThrowingHandlerIsExceptionSafe() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new ThrowingHandler());
        final ResponseValidator response =
                driver.client().newPost("/status.html")
                        .addHeader(CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED)
                        .setContent("a=b")
                        .execute();
        response.expectStatusCode(is(INTERNAL_SERVER_ERROR));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatMultiPostWorks() throws Exception {
        // This is taken from tcpdump of bug 5433352 and reassembled here to see that httpserver passes things on.
        final String startTxtContent = "this is a test for POST.";
        final String updaterConfContent
                = "identifier                      = updater\n"
                + "server_type                     = gds\n";
        final TestDriver driver = TestDrivers.newInstance(new EchoRequestHandler());
        final ResponseValidator response =
                driver.client().newPost("/status.html")
                        .setMultipartContent(
                                newFileBody("", "start.txt", startTxtContent),
                                newFileBody("", "updater.conf", updaterConfContent))
                        .execute();
        response.expectStatusCode(is(OK))
                .expectContent(containsString(startTxtContent))
                .expectContent(containsString(updaterConfContent));
    }

    @Test
    public void requireThatRequestCookiesAreReceived() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new CookiePrinterRequestHandler());
        final ResponseValidator response =
                driver.client().newPost("/status.html")
                      .addHeader(COOKIE, "foo=bar")
                      .execute();
        response.expectStatusCode(is(OK))
                .expectContent(containsString("[foo=bar]"));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatSetCookieHeaderIsCorrect() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new CookieSetterRequestHandler(
                new Cookie("foo", "bar")
                        .setDomain(".localhost")
                        .setHttpOnly(true)
                        .setPath("/foopath")
                        .setSecure(true)));
        driver.client().get("/status.html")
              .expectStatusCode(is(OK))
              .expectHeader("Set-Cookie",
                      is("foo=bar; Path=/foopath; Domain=.localhost; Secure; HttpOnly"));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatTimeoutWorks() throws Exception {
        final UnresponsiveHandler requestHandler = new UnresponsiveHandler();
        final TestDriver driver = TestDrivers.newInstance(requestHandler);
        driver.client().get("/status.html")
              .expectStatusCode(is(GATEWAY_TIMEOUT));
        ResponseDispatch.newInstance(OK).dispatch(requestHandler.responseHandler);
        assertThat(driver.close(), is(true));
    }

    // Header with no value is disallowed by https://tools.ietf.org/html/rfc7230#section-3.2
    // Details in https://github.com/eclipse/jetty.project/issues/1116
    @Test
    public void requireThatHeaderWithNullValueIsOmitted() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new EchoWithHeaderRequestHandler("X-Foo", null));
        driver.client().get("/status.html")
              .expectStatusCode(is(OK))
              .expectNoHeader("X-Foo");
        assertThat(driver.close(), is(true));
    }

    // Header with empty value is allowed by https://tools.ietf.org/html/rfc7230#section-3.2
    // Details in https://github.com/eclipse/jetty.project/issues/1116
    @Test
    public void requireThatHeaderWithEmptyValueIsAllowed() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new EchoWithHeaderRequestHandler("X-Foo", ""));
        driver.client().get("/status.html")
                .expectStatusCode(is(OK))
                .expectHeader("X-Foo", is(""));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatNoConnectionHeaderMeansKeepAliveInHttp11KeepAliveDisabled() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new EchoWithHeaderRequestHandler(CONNECTION, CLOSE));
        driver.client().get("/status.html")
              .expectHeader(CONNECTION, is(CLOSE));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatConnectionIsClosedAfterXRequests() throws Exception {
        final int MAX_KEEPALIVE_REQUESTS = 100;
        final TestDriver driver = TestDrivers.newConfiguredInstance(new EchoRequestHandler(),
                new ServerConfig.Builder(),
                new ConnectorConfig.Builder().maxRequestsPerConnection(MAX_KEEPALIVE_REQUESTS));
        for (int i = 0; i < MAX_KEEPALIVE_REQUESTS - 1; i++) {
            driver.client().get("/status.html")
                    .expectStatusCode(is(OK))
                    .expectNoHeader(CONNECTION);
        }
        driver.client().get("/status.html")
                .expectStatusCode(is(OK))
                .expectHeader(CONNECTION, is(CLOSE));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatServerCanRespondToSslRequest() throws Exception {
        Path privateKeyFile = tmpFolder.newFile().toPath();
        Path certificateFile = tmpFolder.newFile().toPath();
        generatePrivateKeyAndCertificate(privateKeyFile, certificateFile);

        final TestDriver driver = TestDrivers.newInstanceWithSsl(new EchoRequestHandler(), certificateFile, privateKeyFile, TlsClientAuth.WANT);
        driver.client().get("/status.html")
              .expectStatusCode(is(OK));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatTlsClientAuthenticationEnforcerRejectsRequestsForNonWhitelistedPaths() throws IOException {
        Path privateKeyFile = tmpFolder.newFile().toPath();
        Path certificateFile = tmpFolder.newFile().toPath();
        generatePrivateKeyAndCertificate(privateKeyFile, certificateFile);
        TestDriver driver = TestDrivers.newInstanceWithSsl(new EchoRequestHandler(), certificateFile, privateKeyFile, TlsClientAuth.WANT);

        SSLContext trustStoreOnlyCtx = new SslContextBuilder()
                .withTrustStore(certificateFile)
                .build();

        new SimpleHttpClient(trustStoreOnlyCtx, driver.server().getListenPort(), false)
                .get("/dummy.html")
                .expectStatusCode(is(UNAUTHORIZED));

        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatTlsClientAuthenticationEnforcerAllowsRequestForWhitelistedPaths() throws IOException {
        Path privateKeyFile = tmpFolder.newFile().toPath();
        Path certificateFile = tmpFolder.newFile().toPath();
        generatePrivateKeyAndCertificate(privateKeyFile, certificateFile);
        TestDriver driver = TestDrivers.newInstanceWithSsl(new EchoRequestHandler(), certificateFile, privateKeyFile, TlsClientAuth.WANT);

        SSLContext trustStoreOnlyCtx = new SslContextBuilder()
                .withTrustStore(certificateFile)
                .build();

        new SimpleHttpClient(trustStoreOnlyCtx, driver.server().getListenPort(), false)
                .get("/status.html")
                .expectStatusCode(is(OK));

        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatConnectedAtReturnsNonZero() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new ConnectedAtRequestHandler());
        driver.client().get("/status.html")
              .expectStatusCode(is(OK))
              .expectContent(matchesPattern("\\d{13,}"));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatGzipEncodingRequestsAreAutomaticallyDecompressed() throws Exception {
        final TestDriver driver = TestDrivers.newInstance(new ParameterPrinterRequestHandler());
        final String requestContent = generateContent('a', 30);
        final ResponseValidator response =
                driver.client().newPost("/status.html")
                        .addHeader(CONTENT_TYPE, APPLICATION_X_WWW_FORM_URLENCODED)
                        .setGzipContent(requestContent)
                        .execute();
        response.expectStatusCode(is(OK))
                .expectContent(startsWith('{' + requestContent + "=[]}"));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatConnectionThrottleDoesNotBlockConnectionsBelowThreshold() throws Exception {
        final TestDriver driver = TestDrivers.newConfiguredInstance(
                new EchoRequestHandler(),
                new ServerConfig.Builder(),
                new ConnectorConfig.Builder()
                        .throttling(new Throttling.Builder()
                                            .enabled(true)
                                            .maxAcceptRate(10)
                                            .maxHeapUtilization(1.0)
                                            .maxConnections(10)));
        driver.client().get("/status.html")
                .expectStatusCode(is(OK));
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatMetricIsIncrementedWhenClientIsMissingCertificateOnHandshake() throws IOException {
        Path privateKeyFile = tmpFolder.newFile().toPath();
        Path certificateFile = tmpFolder.newFile().toPath();
        generatePrivateKeyAndCertificate(privateKeyFile, certificateFile);
        var metricConsumer = new MetricConsumerMock();
        TestDriver driver = createSslTestDriver(certificateFile, privateKeyFile, metricConsumer);

        SSLContext clientCtx = new SslContextBuilder()
                .withTrustStore(certificateFile)
                .build();
        assertHttpsRequestTriggersSslHandshakeException(
                driver, clientCtx, null, null, "Received fatal alert: bad_certificate");
        verify(metricConsumer.mockitoMock())
                .add(Metrics.SSL_HANDSHAKE_FAILURE_MISSING_CLIENT_CERT, 1L, MetricConsumerMock.STATIC_CONTEXT);
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatMetricIsIncrementedWhenClientUsesIncompatibleTlsVersion() throws IOException {
        Path privateKeyFile = tmpFolder.newFile().toPath();
        Path certificateFile = tmpFolder.newFile().toPath();
        generatePrivateKeyAndCertificate(privateKeyFile, certificateFile);
        var metricConsumer = new MetricConsumerMock();
        TestDriver driver = createSslTestDriver(certificateFile, privateKeyFile, metricConsumer);

        SSLContext clientCtx = new SslContextBuilder()
                .withTrustStore(certificateFile)
                .withKeyStore(privateKeyFile, certificateFile)
                .build();

        assertHttpsRequestTriggersSslHandshakeException(
                driver, clientCtx, "TLSv1.3", null, "Received fatal alert: protocol_version");
        verify(metricConsumer.mockitoMock())
                .add(Metrics.SSL_HANDSHAKE_FAILURE_INCOMPATIBLE_PROTOCOLS, 1L, MetricConsumerMock.STATIC_CONTEXT);
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatMetricIsIncrementedWhenClientUsesIncompatibleCiphers() throws IOException {
        Path privateKeyFile = tmpFolder.newFile().toPath();
        Path certificateFile = tmpFolder.newFile().toPath();
        generatePrivateKeyAndCertificate(privateKeyFile, certificateFile);
        var metricConsumer = new MetricConsumerMock();
        TestDriver driver = createSslTestDriver(certificateFile, privateKeyFile, metricConsumer);

        SSLContext clientCtx = new SslContextBuilder()
                .withTrustStore(certificateFile)
                .withKeyStore(privateKeyFile, certificateFile)
                .build();

        assertHttpsRequestTriggersSslHandshakeException(
                driver, clientCtx, null, "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "Received fatal alert: handshake_failure");
        verify(metricConsumer.mockitoMock())
                .add(Metrics.SSL_HANDSHAKE_FAILURE_INCOMPATIBLE_CIPHERS, 1L, MetricConsumerMock.STATIC_CONTEXT);
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatMetricIsIncrementedWhenClientUsesInvalidCertificateInHandshake() throws IOException {
        Path serverPrivateKeyFile = tmpFolder.newFile().toPath();
        Path serverCertificateFile = tmpFolder.newFile().toPath();
        generatePrivateKeyAndCertificate(serverPrivateKeyFile, serverCertificateFile);
        var metricConsumer = new MetricConsumerMock();
        TestDriver driver = createSslTestDriver(serverCertificateFile, serverPrivateKeyFile, metricConsumer);

        Path clientPrivateKeyFile = tmpFolder.newFile().toPath();
        Path clientCertificateFile = tmpFolder.newFile().toPath();
        generatePrivateKeyAndCertificate(clientPrivateKeyFile, clientCertificateFile);

        SSLContext clientCtx = new SslContextBuilder()
                .withKeyStore(clientPrivateKeyFile, clientCertificateFile)
                .withTrustStore(serverCertificateFile)
                .build();

        assertHttpsRequestTriggersSslHandshakeException(
                driver, clientCtx, null, null, "Received fatal alert: certificate_unknown");
        verify(metricConsumer.mockitoMock())
                .add(Metrics.SSL_HANDSHAKE_FAILURE_INVALID_CLIENT_CERT, 1L, MetricConsumerMock.STATIC_CONTEXT);
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatMetricIsIncrementedWhenClientUsesExpiredCertificateInHandshake() throws IOException {
        Path rootPrivateKeyFile = tmpFolder.newFile().toPath();
        Path rootCertificateFile = tmpFolder.newFile().toPath();
        Path privateKeyFile = tmpFolder.newFile().toPath();
        Path certificateFile = tmpFolder.newFile().toPath();
        Instant notAfter = Instant.now().minus(100, ChronoUnit.DAYS);
        generatePrivateKeyAndCertificate(rootPrivateKeyFile, rootCertificateFile, privateKeyFile, certificateFile, notAfter);
        var metricConsumer = new MetricConsumerMock();
        TestDriver driver = createSslTestDriver(rootCertificateFile, rootPrivateKeyFile, metricConsumer);

        SSLContext clientCtx = new SslContextBuilder()
                .withTrustStore(rootCertificateFile)
                .withKeyStore(privateKeyFile, certificateFile)
                .build();

        assertHttpsRequestTriggersSslHandshakeException(
                driver, clientCtx, null, null, "Received fatal alert: certificate_unknown");
        verify(metricConsumer.mockitoMock())
                .add(Metrics.SSL_HANDSHAKE_FAILURE_EXPIRED_CLIENT_CERT, 1L, MetricConsumerMock.STATIC_CONTEXT);
        assertThat(driver.close(), is(true));
    }

    @Test
    public void requireThatProxyProtocolIsAcceptedAndActualRemoteAddressStoredInAccessLog() throws Exception {
        Path privateKeyFile = tmpFolder.newFile().toPath();
        Path certificateFile = tmpFolder.newFile().toPath();
        generatePrivateKeyAndCertificate(privateKeyFile, certificateFile);
        AccessLogMock accessLogMock = new AccessLogMock();
        TestDriver driver = createSslWithProxyProtocolTestDriver(certificateFile, privateKeyFile, accessLogMock, /*mixedMode*/false);
        HttpClient client = createJettyHttpClient(certificateFile);

        String proxiedRemoteAddress = "192.168.0.100";
        int proxiedRemotePort = 12345;
        sendJettyClientRequest(driver, client, new V1.Tag(proxiedRemoteAddress, proxiedRemotePort));
        sendJettyClientRequest(driver, client, new V2.Tag(proxiedRemoteAddress, proxiedRemotePort));
        client.stop();
        assertThat(driver.close(), is(true));

        assertThat(accessLogMock.logEntries, hasSize(2));
        assertLogEntryHasRemote(accessLogMock.logEntries.get(0), proxiedRemoteAddress, proxiedRemotePort);
        assertLogEntryHasRemote(accessLogMock.logEntries.get(1), proxiedRemoteAddress, proxiedRemotePort);
    }

    @Test
    public void requireThatConnectorWithProxyProtocolMixedEnabledAcceptsBothProxyProtocolAndHttps() throws Exception {
        Path privateKeyFile = tmpFolder.newFile().toPath();
        Path certificateFile = tmpFolder.newFile().toPath();
        generatePrivateKeyAndCertificate(privateKeyFile, certificateFile);
        AccessLogMock accessLogMock = new AccessLogMock();
        TestDriver driver = createSslWithProxyProtocolTestDriver(certificateFile, privateKeyFile, accessLogMock, /*mixedMode*/true);
        HttpClient client = createJettyHttpClient(certificateFile);

        String proxiedRemoteAddress = "192.168.0.100";
        sendJettyClientRequest(driver, client, null);
        sendJettyClientRequest(driver, client, new V2.Tag(proxiedRemoteAddress, 12345));
        client.stop();
        assertThat(driver.close(), is(true));

        assertThat(accessLogMock.logEntries, hasSize(2));
        assertLogEntryHasRemote(accessLogMock.logEntries.get(0), "127.0.0.1", 0);
        assertLogEntryHasRemote(accessLogMock.logEntries.get(1), proxiedRemoteAddress, 0);
    }

    @Test
    public void requireThatJdiscLocalPortPropertyIsNotOverriddenByProxyProtocol() throws Exception {
        Path privateKeyFile = tmpFolder.newFile().toPath();
        Path certificateFile = tmpFolder.newFile().toPath();
        generatePrivateKeyAndCertificate(privateKeyFile, certificateFile);
        AccessLogMock accessLogMock = new AccessLogMock();
        TestDriver driver = createSslWithProxyProtocolTestDriver(certificateFile, privateKeyFile, accessLogMock, /*mixedMode*/false);
        HttpClient client = createJettyHttpClient(certificateFile);

        String proxiedRemoteAddress = "192.168.0.100";
        int proxiedRemotePort = 12345;
        String proxyLocalAddress = "10.0.0.10";
        int proxyLocalPort = 23456;
        V2.Tag v2Tag = new V2.Tag(V2.Tag.Command.PROXY, null, V2.Tag.Protocol.STREAM,
                                  proxiedRemoteAddress, proxiedRemotePort, proxyLocalAddress, proxyLocalPort, null);
        ContentResponse response = sendJettyClientRequest(driver, client, v2Tag);
        client.stop();
        assertThat(driver.close(), is(true));

        int clientPort = Integer.parseInt(response.getHeaders().get("Jdisc-Local-Port"));
        assertNotEquals(proxyLocalPort, clientPort);
    }

    private ContentResponse sendJettyClientRequest(TestDriver testDriver, HttpClient client, Object tag)
            throws InterruptedException, TimeoutException {
        int maxAttempts = 3;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                ContentResponse response = client.newRequest(URI.create("https://localhost:" + testDriver.server().getListenPort() + "/"))
                        .tag(tag)
                        .send();
                assertEquals(200, response.getStatus());
                return response;
            } catch (ExecutionException e) {
                // Retry when the server closes the connection before the TLS handshake is completed. This have been observed in CI.
                // We have been unable to reproduce this locally. The cause is therefor currently unknown.
                log.log(Level.WARNING, String.format("Attempt %d failed: %s", attempt, e.getMessage()), e);
                Thread.sleep(10);
            }
        }
        throw new AssertionError("Failed to send request, see log for details");
    }

    // Using Jetty's http client as Apache httpclient does not support the proxy-protocol v1/v2.
    private static HttpClient createJettyHttpClient(Path certificateFile) throws Exception {
        SslContextFactory.Client clientSslCtxFactory = new SslContextFactory.Client();
        clientSslCtxFactory.setHostnameVerifier(NoopHostnameVerifier.INSTANCE);
        clientSslCtxFactory.setSslContext(new SslContextBuilder().withTrustStore(certificateFile).build());

        HttpClient client = new HttpClient(clientSslCtxFactory);
        client.start();
        return client;
    }

    private static void assertLogEntryHasRemote(AccessLogEntry entry, String expectedAddress, int expectedPort) {
        assertEquals(expectedAddress, entry.getPeerAddress());
        if (expectedPort > 0) {
            assertEquals(expectedPort, entry.getPeerPort());
        }
    }

    private static TestDriver createSslWithProxyProtocolTestDriver(
            Path certificateFile, Path privateKeyFile, AccessLogMock accessLogMock, boolean mixedMode) throws IOException {
        ConnectorConfig.Builder connectorConfig = new ConnectorConfig.Builder()
                .proxyProtocol(new ConnectorConfig.ProxyProtocol.Builder()
                                       .enabled(true)
                                       .mixedMode(mixedMode))
                .ssl(new ConnectorConfig.Ssl.Builder()
                             .enabled(true)
                             .privateKeyFile(privateKeyFile.toString())
                             .certificateFile(certificateFile.toString())
                             .caCertificateFile(certificateFile.toString()));
        return TestDrivers.newConfiguredInstance(
                new EchoRequestHandler(),
                new ServerConfig.Builder(),
                connectorConfig,
                binder -> binder.bind(AccessLog.class).toInstance(accessLogMock));
    }

    private static TestDriver createSslTestDriver(
            Path serverCertificateFile, Path serverPrivateKeyFile, MetricConsumerMock metricConsumer) throws IOException {
        return TestDrivers.newInstanceWithSsl(
                new EchoRequestHandler(), serverCertificateFile, serverPrivateKeyFile, TlsClientAuth.NEED, metricConsumer.asGuiceModule());
    }

    private static void assertHttpsRequestTriggersSslHandshakeException(
            TestDriver testDriver,
            SSLContext sslContext,
            String protocolOverride,
            String cipherOverride,
            String expectedExceptionSubstring) throws IOException {
        List<String> protocols = protocolOverride != null ? List.of(protocolOverride) : null;
        List<String> ciphers = cipherOverride != null ? List.of(cipherOverride) : null;
        try (var client = new SimpleHttpClient(sslContext, protocols, ciphers, testDriver.server().getListenPort(), false)) {
            client.get("/status.html");
            fail("SSLHandshakeException expected");
        } catch (SSLHandshakeException e) {
            assertThat(e.getMessage(), containsString(expectedExceptionSubstring));
        } catch (SSLException e) {
            // This exception is thrown if Apache httpclient's write thread detects the handshake failure before the read thread.
            log.log(Level.WARNING, "Client failed to get a proper TLS handshake response: " + e.getMessage(), e);
            assertThat(e.getMessage(), containsString("readHandshakeRecord")); // Only ignore this specific ssl exception
        }
    }

    private static void generatePrivateKeyAndCertificate(Path privateKeyFile, Path certificateFile) throws IOException {
        KeyPair keyPair = KeyUtils.generateKeypair(RSA, 2048);
        Files.writeString(privateKeyFile, KeyUtils.toPem(keyPair.getPrivate()));

        X509Certificate certificate = X509CertificateBuilder
                .fromKeypair(
                        keyPair, new X500Principal("CN=localhost"), Instant.EPOCH, Instant.EPOCH.plus(100_000, ChronoUnit.DAYS), SHA256_WITH_RSA, BigInteger.ONE)
                .build();
        Files.writeString(certificateFile, X509CertificateUtils.toPem(certificate));
    }

    private static void generatePrivateKeyAndCertificate(Path rootPrivateKeyFile, Path rootCertificateFile,
                                                         Path privateKeyFile, Path certificateFile, Instant notAfter) throws IOException {
        generatePrivateKeyAndCertificate(rootPrivateKeyFile, rootCertificateFile);
        X509Certificate rootCertificate = X509CertificateUtils.fromPem(Files.readString(rootCertificateFile));
        PrivateKey privateKey = KeyUtils.fromPemEncodedPrivateKey(Files.readString(rootPrivateKeyFile));

        KeyPair keyPair = KeyUtils.generateKeypair(RSA, 2048);
        Files.writeString(privateKeyFile, KeyUtils.toPem(keyPair.getPrivate()));
        Pkcs10Csr csr = Pkcs10CsrBuilder.fromKeypair(new X500Principal("CN=myclient"), keyPair, SHA256_WITH_RSA).build();
        X509Certificate certificate = X509CertificateBuilder
                .fromCsr(csr, rootCertificate.getSubjectX500Principal(), Instant.EPOCH, notAfter, privateKey, SHA256_WITH_RSA, BigInteger.ONE)
                .build();
        Files.writeString(certificateFile, X509CertificateUtils.toPem(certificate));
    }

    private static RequestHandler mockRequestHandler() {
        final RequestHandler mockRequestHandler = mock(RequestHandler.class);
        when(mockRequestHandler.refer()).thenReturn(References.NOOP_REFERENCE);
        return mockRequestHandler;
    }

    private static String generateContent(final char c, final int len) {
        final StringBuilder ret = new StringBuilder(len);
        for (int i = 0; i < len; ++i) {
            ret.append(c);
        }
        return ret.toString();
    }

    private static TestDriver newDriverWithFormPostContentRemoved(
            final RequestHandler requestHandler, final boolean removeFormPostBody) throws Exception {
        return TestDrivers.newConfiguredInstance(
                requestHandler,
                new ServerConfig.Builder()
                        .removeRawPostBodyForWwwUrlEncodedPost(removeFormPostBody),
                new ConnectorConfig.Builder());
    }

    private static FormBodyPart newFileBody(final String parameterName, final String fileName, final String fileContent)
            throws Exception {
        return new FormBodyPart(
                parameterName,
                new StringBody(fileContent, ContentType.TEXT_PLAIN) {
                    @Override
                    public String getFilename() {
                        return fileName;
                    }

                    @Override
                    public String getTransferEncoding() {
                        return "binary";
                    }

                    @Override
                    public String getMimeType() {
                        return "";
                    }

                    @Override
                    public String getCharset() {
                        return null;
                    }
                });
    }

    private static class ConnectedAtRequestHandler extends AbstractRequestHandler {

        @Override
        public ContentChannel handleRequest(final Request request, final ResponseHandler handler) {
            final HttpRequest httpRequest = (HttpRequest)request;
            final String connectedAt = String.valueOf(httpRequest.getConnectedAt(TimeUnit.MILLISECONDS));
            final ContentChannel ch = handler.handleResponse(new Response(OK));
            ch.write(ByteBuffer.wrap(connectedAt.getBytes(StandardCharsets.UTF_8)), null);
            ch.close(null);
            return null;
        }
    }

    private static class CookieSetterRequestHandler extends AbstractRequestHandler {

        final Cookie cookie;

        CookieSetterRequestHandler(final Cookie cookie) {
            this.cookie = cookie;
        }

        @Override
        public ContentChannel handleRequest(final Request request, final ResponseHandler handler) {
            final HttpResponse response = HttpResponse.newInstance(OK);
            response.encodeSetCookieHeader(Collections.singletonList(cookie));
            ResponseDispatch.newInstance(response).dispatch(handler);
            return null;
        }
    }

    private static class CookiePrinterRequestHandler extends AbstractRequestHandler {

        @Override
        public ContentChannel handleRequest(final Request request, final ResponseHandler handler) {
            final List<Cookie> cookies = new ArrayList<>(((HttpRequest)request).decodeCookieHeader());
            Collections.sort(cookies, new CookieComparator());
            final ContentChannel out = ResponseDispatch.newInstance(Response.Status.OK).connect(handler);
            out.write(StandardCharsets.UTF_8.encode(cookies.toString()), null);
            out.close(null);
            return null;
        }
    }

    private static class ParameterPrinterRequestHandler extends AbstractRequestHandler {
        private static final CompletionHandler NULL_COMPLETION_HANDLER = null;

        @Override
        public ContentChannel handleRequest(final Request request, final ResponseHandler handler) {
            final Map<String, List<String>> parameters =
                    new TreeMap<>(((HttpRequest)request).parameters());
            final ContentChannel responseContentChannel
                    = ResponseDispatch.newInstance(Response.Status.OK).connect(handler);
            responseContentChannel.write(
                    ByteBuffer.wrap(parameters.toString().getBytes(StandardCharsets.UTF_8)),
                    NULL_COMPLETION_HANDLER);

            // Have the request content written back to the response.
            return responseContentChannel;
        }
    }

    private static class ThrowingHandler extends AbstractRequestHandler {
        @Override
        public ContentChannel handleRequest(final Request request, final ResponseHandler handler) {
            throw new RuntimeException("Deliberately thrown exception");
        }
    }

    private static class UnresponsiveHandler extends AbstractRequestHandler {

        ResponseHandler responseHandler;

        @Override
        public ContentChannel handleRequest(final Request request, final ResponseHandler handler) {
            request.setTimeout(100, TimeUnit.MILLISECONDS);
            responseHandler = handler;
            return null;
        }
    }

    private static class EchoRequestHandler extends AbstractRequestHandler {

        @Override
        public ContentChannel handleRequest(final Request request, final ResponseHandler handler) {
            int port = request.getUri().getPort();
            Response response = new Response(OK);
            response.headers().put("Jdisc-Local-Port", Integer.toString(port));
            return handler.handleResponse(response);
        }
    }

    private static class EchoWithHeaderRequestHandler extends AbstractRequestHandler {

        final String headerName;
        final String headerValue;

        EchoWithHeaderRequestHandler(final String headerName, final String headerValue) {
            this.headerName = headerName;
            this.headerValue = headerValue;
        }

        @Override
        public ContentChannel handleRequest(final Request request, final ResponseHandler handler) {
            final Response response = new Response(OK);
            response.headers().add(headerName, headerValue);
            return handler.handleResponse(response);
        }
    }

    private static Module newBindingSetSelector(final String setName) {
        return new AbstractModule() {

            @Override
            protected void configure() {
                bind(BindingSetSelector.class).toInstance(new BindingSetSelector() {

                    @Override
                    public String select(final URI uri) {
                        return setName;
                    }
                });
            }
        };
    }

    private static class CookieComparator implements Comparator<Cookie> {

        @Override
        public int compare(final Cookie lhs, final Cookie rhs) {
            return lhs.getName().compareTo(rhs.getName());
        }
    }

    private static class MetricConsumerMock {
        static final Metric.Context STATIC_CONTEXT = new Metric.Context() {};

        private final MetricConsumer mockitoMock = mock(MetricConsumer.class);

        MetricConsumerMock() {
            when(mockitoMock.createContext(anyMap())).thenReturn(STATIC_CONTEXT);
        }

        MetricConsumer mockitoMock() { return mockitoMock; }
        Module asGuiceModule() { return binder -> binder.bind(MetricConsumer.class).toInstance(mockitoMock); }
    }

}
