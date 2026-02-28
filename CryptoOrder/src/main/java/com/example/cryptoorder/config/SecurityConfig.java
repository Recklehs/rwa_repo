package com.example.cryptoorder.config;

import com.example.cryptoorder.common.api.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/**", "/api/auth/**", "/.well-known/jwks.json", "/h2-console/**").permitAll()
                        .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeSecurityError(response, objectMapper, HttpStatus.UNAUTHORIZED, "인증이 필요합니다.", request.getRequestURI()))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeSecurityError(response, objectMapper, HttpStatus.FORBIDDEN, "접근 권한이 없습니다.", request.getRequestURI())))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    private void writeSecurityError(
            jakarta.servlet.http.HttpServletResponse response,
            ObjectMapper objectMapper,
            HttpStatus status,
            String message,
            String path
    ) throws IOException {
        ErrorResponse body = new ErrorResponse(
                LocalDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path
        );
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 1. 기본으로 사용할 암호화 ID 지정 (현재 가장 무난한 bcrypt 사용)
        String idForEncode = "bcrypt";

        // 2. 지원할 암호화 인코더 목록 생성
        Map<String, PasswordEncoder> encoders = new HashMap<>();

        // Bcrypt 설정 (Strength 10~12 권장)
        encoders.put("bcrypt", new BCryptPasswordEncoder());

        // 추후 컴플라이언스 기준에 맞춰서 암호화 강화 필요 시 전환 가능

        return new DelegatingPasswordEncoder(idForEncode, encoders);
    }
}
