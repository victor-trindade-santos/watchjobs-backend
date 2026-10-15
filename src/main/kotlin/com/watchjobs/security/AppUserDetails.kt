package com.watchjobs.security

import com.watchjobs.entity.User
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

class AppUserDetails(
    val user: User
) : UserDetails {
    val id: Int
        get() = user.id ?: error("Usuário sem id")
    
        override fun getUsername(): String = user.email

        override fun getPassword(): String = user.password

        override fun getAuthorities(): Collection<GrantedAuthority> = emptyList()
}
