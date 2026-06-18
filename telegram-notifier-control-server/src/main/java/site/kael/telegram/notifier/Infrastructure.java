package site.kael.telegram.notifier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
class Infrastructure implements WebMvcConfigurer {
    private final AdminService adminService;
    private final TokenService tokenService;

    Infrastructure(AdminService adminService, TokenService tokenService) {
        this.adminService = adminService;
        this.tokenService = tokenService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiAuthInterceptor(adminService, tokenService)).addPathPatterns("/api/**");
    }

    @Bean
    FilterRegistrationBean<SpaFallbackFilter> spaFallbackFilter() {
        var bean = new FilterRegistrationBean<>(new SpaFallbackFilter());
        bean.setOrder(Integer.MAX_VALUE);
        return bean;
    }
}

final class SpaFallbackFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, jakarta.servlet.FilterChain filterChain)
            throws java.io.IOException, jakarta.servlet.ServletException {
        var path = request.getRequestURI();
        if ("GET".equalsIgnoreCase(request.getMethod())
                && !path.startsWith("/api/")
                && !path.contains(".")
                && !"/index.html".equals(path)) {
            request.getRequestDispatcher("/index.html").forward(request, response);
            return;
        }
        filterChain.doFilter(request, response);
    }
}

final class ApiAuthInterceptor implements HandlerInterceptor {
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/system/bootstrap-status",
            "/api/system/admin-init",
            "/api/auth/login"
    );
    private final AdminService adminService;
    private final TokenService tokenService;

    ApiAuthInterceptor(AdminService adminService, TokenService tokenService) {
        this.adminService = adminService;
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || PUBLIC_PATHS.contains(request.getRequestURI())) {
            return true;
        }
        if (!adminService.hasAdmin()) {
            return true;
        }
        var token = request.getHeader("X-Auth-Token");
        if (tokenService.isValid(token)) {
            return true;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthorized\"}");
        return false;
    }
}

@Configuration
class SqliteConfiguration {
    @Bean
    CommandLineRunner enableSqliteForeignKeys(JdbcTemplate jdbcTemplate) {
        return args -> jdbcTemplate.execute("PRAGMA foreign_keys = ON");
    }
}

final class TokenService {
    private final Set<String> tokens = ConcurrentHashMap.newKeySet();

    String issue() {
        var token = java.util.UUID.randomUUID().toString();
        tokens.add(token);
        return token;
    }

    boolean isValid(String token) {
        return token != null && tokens.contains(token);
    }

    void revoke(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }
}

@Configuration
class TokenConfiguration {
    @Bean
    TokenService tokenService() {
        return new TokenService();
    }
}
