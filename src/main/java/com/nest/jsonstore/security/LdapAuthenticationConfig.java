package com.nest.jsonstore.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.ldap.LdapBindAuthenticationManagerFactory;
import org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator;
import org.springframework.security.ldap.userdetails.LdapUserDetailsMapper;
import org.springframework.util.StringUtils;

/**
 * Authenticates by binding to the directory as the user, then reads the groups they belong to.
 * A group becomes a role: {@code cn=admins} becomes {@code ROLE_ADMINS}.
 */
@Configuration
class LdapAuthenticationConfig {

    @Bean
    AuthenticationManager authenticationManager(BaseLdapPathContextSource contextSource, SecurityProperties properties) {
        SecurityProperties.Ldap ldap = properties.ldap();
        LdapBindAuthenticationManagerFactory factory = new LdapBindAuthenticationManagerFactory(contextSource);

        if (StringUtils.hasText(ldap.userDnPatterns())) {
            factory.setUserDnPatterns(ldap.userDnPatterns());
        } else {
            factory.setUserSearchBase(ldap.userSearchBase());
            factory.setUserSearchFilter(ldap.userSearchFilter());
        }

        factory.setUserDetailsContextMapper(new LdapUserDetailsMapper());
        if (StringUtils.hasText(ldap.groupSearchBase())) {
            factory.setLdapAuthoritiesPopulator(authoritiesPopulator(contextSource, ldap));
        }
        return factory.createAuthenticationManager();
    }

    private static DefaultLdapAuthoritiesPopulator authoritiesPopulator(
            BaseLdapPathContextSource contextSource, SecurityProperties.Ldap ldap) {
        var populator = new DefaultLdapAuthoritiesPopulator(contextSource, ldap.groupSearchBase());
        populator.setGroupSearchFilter(ldap.groupSearchFilter());
        populator.setRolePrefix("ROLE_");
        populator.setConvertToUpperCase(true);
        return populator;
    }
}
