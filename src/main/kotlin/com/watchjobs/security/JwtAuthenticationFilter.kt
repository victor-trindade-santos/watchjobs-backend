package com.watchjobs.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.web.filter.OncePerRequestFilter

class JwtAuthenticationFilter(
    private val jwtUtil: JwtUtil,
    private val userDetailsService: AppUserDetailsService
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) {
            val token = header.substring(7)
            if (jwtUtil.isValid(token)) {
                val userDetails = try {
                    userDetailsService.loadUserByUsername(jwtUtil.extractUsername(token))
                } catch (ex: UsernameNotFoundException) {
                    null
                }
                val changedAt = (userDetails as? AppUserDetails)?.user?.passwordChangedAt
                val issuedBeforeChange = changedAt != null && jwtUtil.issuedAt(token).isBefore(changedAt)
                if (userDetails != null && !issuedBeforeChange) {
                    val authentication = UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.authorities
                    )
                    SecurityContextHolder.getContext().authentication = authentication
                }
            }
        }        
        filterChain.doFilter(request, response)
    }
}