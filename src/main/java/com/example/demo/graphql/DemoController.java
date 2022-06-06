package com.example.demo.graphql;

import com.example.demo.person.Person;
import com.example.demo.person.PersonService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Controller
@RequiredArgsConstructor
public class DemoController {

    private final PersonService service;

    @QueryMapping
    public Mono<String> ping() {
        return Mono.just("pong");
    }

    @QueryMapping
    public Mono<String> me() {
        return getCurrentUsersUsername();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @QueryMapping
    public Flux<Person> persons() {
        return service.findAllPersons();
    }

    private static Mono<String> getCurrentUsersUsername() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(authentication -> {
                    if (authentication.getPrincipal() != null && authentication.getPrincipal() instanceof Jwt principal)
                        return principal.getClaimAsString("preferred_username");

                    return null;
                });
    }

}
