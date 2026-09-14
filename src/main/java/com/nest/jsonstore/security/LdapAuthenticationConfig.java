package com.nest.jsonstore.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.ldap.LdapBindAuthenticationManagerFactory;
import org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator;
import org.springframework.security.ldap.userdetails.LdapUserDetailsMapper;
import org.springframework.util.StringUtils;

import java.util.Collection;

/**
 * Authenticates by binding to the directory as the user, then reads the groups they belong to.
 * A group is read as {@code cn=admins} becoming {@code ROLE_ADMINS}; which groups grant what in JSON
 * Store is decided afterwards, by {@link DirectoryRoles}.
 */
@Configuration
class LdapAuthenticationConfig {

    @Bean
    AuthenticationManager authenticationManager(BaseLdapPathContextSource contextSource, SecurityProperties properties,
                                                AccessProperties access) {
        SecurityProperties.Ldap ldap = properties.ldap();
        LdapBindAuthenticationManagerFactory factory = new LdapBindAuthenticationManagerFactory(contextSource);

        if (StringUtils.hasText(ldap.userDnPatterns())) {
            factory.setUserDnPatterns(ldap.userDnPatterns());
        } else {
            factory.setUserSearchBase(ldap.userSearchBase());
            factory.setUserSearchFilter(ldap.userSearchFilter());
        }

        factory.setUserDetailsContextMapper(new CanonicalUsernameMapper(access.usernameAttribute()));
        if (StringUtils.hasText(ldap.groupSearchBase())) {
            factory.setLdapAuthoritiesPopulator(authoritiesPopulator(contextSource, ldap));
        }
        return factory.createAuthenticationManager();
    }

    /**
     * Names the user the way the directory spells them. A bind usually ignores case, so without this
     * "ALICE" and "alice" would be recorded as two different authors of the same profiles.
     */
    static final class CanonicalUsernameMapper extends LdapUserDetailsMapper {

        private final String attribute;

        CanonicalUsernameMapper(String attribute) {
            this.attribute = attribute;
        }

        @Override
        public UserDetails mapUserFromContext(DirContextOperations context, String username,
                                              Collection<? extends GrantedAuthority> authorities) {
            String canonical = context.getStringAttribute(attribute);
            return super.mapUserFromContext(context, StringUtils.hasText(canonical) ? canonical : username, authorities);
        }
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
