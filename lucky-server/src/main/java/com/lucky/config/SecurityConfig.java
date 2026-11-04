package com.lucky.config;

import com.lucky.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security配置
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 禁用CSRF
                .csrf(AbstractHttpConfigurer::disable)
                // 无状态Session
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 请求授权
                .authorizeHttpRequests(auth -> auth
                        // 公开接口 - 活动查询
                        .requestMatchers(HttpMethod.GET, "/api/activity/current").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/activity/history").permitAll()
                        // 公开接口 - 参与者
                        .requestMatchers("/api/participant/**").permitAll()
                        // 公开接口 - 弹幕发送
                        .requestMatchers(HttpMethod.POST, "/api/danmaku/send").permitAll()
                        // 公开接口 - 屏幕配置查询
                        .requestMatchers(HttpMethod.GET, "/api/screen/config").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/screen/danmaku-settings").permitAll()
                        // 公开接口 - WebSocket
                        .requestMatchers("/ws/**").permitAll()
                        // 公开接口 - Swagger
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        // 公开接口 - 健康检查
                        .requestMatchers("/actuator/**").permitAll()
                        // 公开接口 - 登录
                        .requestMatchers("/api/auth/login").permitAll()
                        // 公开接口 - 扫码登录（移动端用户没有 JWT，凭 Redis 临时令牌鉴权）
                        .requestMatchers("/api/scan/**").permitAll()
                        // 公开接口 - WebSocket 在线人数（大屏/移动端轮询）
                        .requestMatchers("/api/ws/**").permitAll()
                        // 抢奖接口：用户端没有 JWT，身份由扫码临时令牌在 Controller 内校验
                        .requestMatchers(HttpMethod.POST, "/api/lottery/join/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/lottery/result").permitAll()
                        // 抽奖只读查询：大屏和手机端都要读，属于公开信息
                        .requestMatchers(HttpMethod.GET, "/api/lottery/rounds").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/lottery/winners").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/lottery/remaining").permitAll()
                        // 其他接口需要认证
                        .anyRequest().authenticated()
                )
                // 添加JWT过滤器
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
