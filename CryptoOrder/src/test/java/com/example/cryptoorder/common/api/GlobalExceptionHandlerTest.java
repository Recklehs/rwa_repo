package com.example.cryptoorder.common.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(GlobalExceptionHandlerTest.TestErrorController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedRequest_isRejectedBySecurity() throws Exception {
        mockMvc.perform(get("/test/errors/illegal-argument").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."))
                .andExpect(jsonPath("$.path").value("/test/errors/illegal-argument"));
    }

    @Test
    @WithMockUser(username = "tester")
    void illegalArgumentException_returnsStandardErrorResponse() throws Exception {
        mockMvc.perform(get("/test/errors/illegal-argument").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("요청 파라미터가 잘못되었습니다."))
                .andExpect(jsonPath("$.path").value("/test/errors/illegal-argument"));
    }

    @Test
    @WithMockUser(username = "tester")
    void upstreamServiceException_returns503() throws Exception {
        mockMvc.perform(get("/test/errors/upstream").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value("외부 서비스 호출 실패"))
                .andExpect(jsonPath("$.path").value("/test/errors/upstream"));
    }

    @RestController
    static class TestErrorController {
        @GetMapping("/test/errors/illegal-argument")
        public String throwIllegalArgument() {
            throw new IllegalArgumentException("요청 파라미터가 잘못되었습니다.");
        }

        @GetMapping("/test/errors/upstream")
        public String throwUpstream() {
            throw new UpstreamServiceException("외부 서비스 호출 실패");
        }
    }
}
