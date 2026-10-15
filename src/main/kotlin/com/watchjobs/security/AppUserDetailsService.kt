package com.watchjobs.security

import com.watchjobs.repository.UserRepository
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class AppUserDetailsService(
    private val userRepository: UserRepository
) : UserDetailsService {
    override fun loadUserByUsername(username: String): UserDetails {
        val user = userRepository.findByEmailIgnoreCase(username)
            ?: throw UsernameNotFoundException("Usuário não encontrado com email: $username")
        return AppUserDetails(user)
    }
}