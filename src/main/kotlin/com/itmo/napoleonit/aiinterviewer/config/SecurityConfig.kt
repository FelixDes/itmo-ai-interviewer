package com.itmo.napoleonit.aiinterviewer.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Минимальный Spring Security (Р-20): пользователи в памяти, сессия в куке.
 * Auth здесь не предмет проекта — он нужен, чтобы разграничение доступа
 * было настоящим.
 */
@Configuration
class SecurityConfig {

    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun userDetailsService(): UserDetailsService = InMemoryUserDetailsManager(
        User.withUsername("recruiter").password("{noop}recruiter").roles("RECRUITER").build(),
        User.withUsername("anna").password("{noop}anna").roles("RECRUITER").build(),
    )

    @Bean
    fun authenticationManager(uds: UserDetailsService, encoder: PasswordEncoder): AuthenticationManager =
        ProviderManager(DaoAuthenticationProvider(uds).apply { setPasswordEncoder(encoder) })

    @Bean
    fun securityContextRepository(): SecurityContextRepository = HttpSessionSecurityContextRepository()

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsSource()) }
            .authorizeHttpRequests {
                it.requestMatchers(
                    "/api/auth/login",
                    "/api/auth/logout",
                    "/api/s/**",        // кандидат: доступ по токену в URL
                    "/api/r/**",        // нанимающий менеджер: доступ по токену в URL
                    "/api/demo/**",     // сид демо-данных
                    "/actuator/**",
                ).permitAll()
                it.anyRequest().authenticated()
            }
            .exceptionHandling {
                // API отвечает кодом, а не редиректом на форму логина
                it.authenticationEntryPoint { _, response, _ ->
                    response.status = 401
                    response.contentType = "application/json"
                    response.writer.write("""{"code":"UNAUTHENTICATED","message":"Требуется вход","details":null}""")
                }
            }
        return http.build()
    }

    /** Vite dev-сервер живёт на другом порту, поэтому в деве нужен CORS с куками. */
    private fun corsSource(): UrlBasedCorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOriginPatterns = listOf("http://localhost:*", "http://127.0.0.1:*")
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            allowCredentials = true
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }
}
