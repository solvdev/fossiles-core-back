package com.fossiles.fossilescorebackend.infrastructure.config;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.JwtUtil;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    private final JwtUtil jwt = mock(JwtUtil.class);
    private final UserRepository users = mock(UserRepository.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwt, users);
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sameTokenStopsWorkingAsSoonAsUserIsInactive() throws Exception {
        when(jwt.extractUsername("token")).thenReturn("operador");
        when(jwt.validateToken("token", "operador")).thenReturn(true);
        when(jwt.extractUserId("token")).thenReturn(7L);
        var user = UserEntity.builder().id(7L).username("operador").status("active").build();
        when(users.findByUsername("operador")).thenReturn(Optional.of(user));
        var first = request();
        filter.doFilter(first, new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("operador");
        verify(chain).doFilter(eq(first), any());

        SecurityContextHolder.clearContext();
        user.setStatus("inactive");
        var second = request();
        var response = new MockHttpServletResponse();
        filter.doFilter(second, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, never()).doFilter(eq(second), any());
    }

    @Test
    void deletedUserCannotUseAnUnexpiredToken() throws Exception {
        when(jwt.extractUsername("token")).thenReturn("operador");
        when(jwt.validateToken("token", "operador")).thenReturn(true);
        when(users.findByUsername("operador")).thenReturn(Optional.empty());
        var response = new MockHttpServletResponse();
        filter.doFilter(request(), response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void usernameReusedByAnotherUserDoesNotAcceptPreviousToken() throws Exception {
        when(jwt.extractUsername("token")).thenReturn("operador");
        when(jwt.validateToken("token", "operador")).thenReturn(true);
        when(jwt.extractUserId("token")).thenReturn(7L);
        when(users.findByUsername("operador")).thenReturn(Optional.of(
                UserEntity.builder().id(8L).username("operador").status("active").build()));
        var response = new MockHttpServletResponse();
        filter.doFilter(request(), response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void expiredTokenOnPublicRouteContinuesAsAnonymous() throws Exception {
        when(jwt.extractUsername("token")).thenThrow(new IllegalStateException("Token vencido"));
        var request = new MockHttpServletRequest("GET", "/api/public/materials/5");
        request.addHeader("Authorization", "Bearer token");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(eq(request), any());
    }

    @Test
    void expiredTokenOnPrivateRouteIsRejected() throws Exception {
        when(jwt.extractUsername("token")).thenThrow(new IllegalStateException("Token vencido"));
        var request = new MockHttpServletRequest("POST", "/api/purchase-orders");
        request.addHeader("Authorization", "Bearer token");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void tokenInQueryStringIsOnlyReadByAnnouncementStream() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/tasks");
        request.setParameter("token", "token");
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        verifyNoInteractions(jwt);
        verify(chain).doFilter(eq(request), any());
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/tasks");
        request.addHeader("Authorization", "Bearer token");
        return request;
    }
}
