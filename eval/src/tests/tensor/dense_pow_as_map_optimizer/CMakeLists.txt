# Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
find_package(GTest REQUIRED)
vespa_add_executable(eval_dense_pow_as_map_optimizer_test_app TEST
    SOURCES
    dense_pow_as_map_optimizer_test.cpp
    DEPENDS
    vespaeval
    GTest::GTest
)
vespa_add_test(NAME eval_dense_pow_as_map_optimizer_test_app COMMAND eval_dense_pow_as_map_optimizer_test_app)
