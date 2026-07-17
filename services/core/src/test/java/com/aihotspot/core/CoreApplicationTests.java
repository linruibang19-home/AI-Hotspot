package com.aihotspot.core;

import javax.sql.DataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CoreApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Test
    void contextLoads() {
    }

    @Test
    void myBatisUsesTheSpringManagedDataSource() {
        assertNotNull(sqlSessionFactory);
        assertSame(dataSource, sqlSessionFactory.getConfiguration().getEnvironment().getDataSource());
    }

    @Test
    void healthEndpointPreservesCorrelationId() throws Exception {
        mockMvc.perform(get("/api/v1/health").header("X-Correlation-ID", "test-correlation"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", "test-correlation"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("core-api"))
                .andExpect(jsonPath("$.correlationId").value("test-correlation"));
    }

    @Test
    void csrfEndpointIsPublicAndReturnsToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void anonymousUserCannotReadAdminSources() throws Exception {
        mockMvc.perform(get("/api/v1/admin/sources"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void regularUserCannotReadAdminSources() throws Exception {
        mockMvc.perform(get("/api/v1/admin/sources").with(user("user@example.com").roles("USER")))
                .andExpect(status().isForbidden());
    }

}
