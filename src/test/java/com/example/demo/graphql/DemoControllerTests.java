package com.example.demo.graphql;

import com.example.demo.person.Person;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureHttpGraphQlTester
@Testcontainers
public class DemoControllerTests {

    private static final String KEYCLOAK_USERNAME = "user";
    private static final String KEYCLOAK_PASSWORD = "P@ssw0rd";

    @Container
    private static final MongoDBContainer MONGO_DB_CONTAINER = new MongoDBContainer("mongo:5.0")
            .withCopyFileToContainer(MountableFile.forClasspathResource("data/persons.json"), "data/persons.json")
            .withExposedPorts(27017);

    @Container
    private static final KeycloakContainer KEYCLOAK_CONTAINER = new KeycloakContainer("quay.io/keycloak/keycloak:18.0.0")
            .withRealmImportFile("data/realm-export.json")
            .withExposedPorts(8080);

    @DynamicPropertySource
    private static void registerPgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> KEYCLOAK_CONTAINER.getAuthServerUrl() + "realms/test-realm");
        registry.add("spring.data.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
    }

    private static String keycloakClientSecret;
    private static String authorizationHeader;

    @BeforeAll
    static void setup() throws IOException, InterruptedException {
        setupKeycloak();
        setAuthHeader();
        setupMongodb();
    }

    @Autowired
    private HttpGraphQlTester graphQlTester;

    @Test
    void pingQuery_shouldReturnPong() {
        graphQlTester.document("""
                        {
                            ping
                        }
                        """)
                .execute()
                .path("$.data.ping").entity(String.class).isEqualTo("pong");
    }

    @Test
    void meQuery_shouldReturnNull() {
        graphQlTester.document("""
                        {
                            me
                        }
                        """)
                .execute()
                .path("$.data.me").valueIsNull();
    }

    @Test
    void meQuery_shouldReturnUsername() {
        getAuthenticatedGraphQlTester().document("""
                        {
                            me
                        }
                        """)
                .execute()
                .path("$.data.me").entity(String.class).isEqualTo("user");
    }

    @Test
    void personsQuery_shouldFailIfNotAuthenticated() {
        graphQlTester.document("""
                        {
                            persons {
                                firstName
                                lastName
                            }
                        }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors.size()).isEqualTo(1);
                    assertThat(errors.get(0).getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED);
                });
    }

    @Test
    void personsQuery_shouldReturnAllPersons() {
        getAuthenticatedGraphQlTester().document("""
                        {
                            persons {
                                firstName
                                lastName
                            }
                        }
                        """)
                .execute()
                .path("$.data.persons").entityList(Person.class).hasSize(5);
    }

    private HttpGraphQlTester getAuthenticatedGraphQlTester() {
        return graphQlTester.mutate()
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                .build();
    }

    private static void setupKeycloak() {
        Keycloak keycloak = KEYCLOAK_CONTAINER.getKeycloakAdminClient();
        RealmResource realm = keycloak.realm("test-realm");
        UsersResource users = realm.users();
        ClientRepresentation client = realm.clients().findByClientId("demo").get(0);

        UserRepresentation userRepresentation = new UserRepresentation();
        userRepresentation.setEnabled(true);
        userRepresentation.setUsername(KEYCLOAK_USERNAME);
        UserResource user = users.get(CreatedResponseUtil.getCreatedId(users.create(userRepresentation)));

        CredentialRepresentation credentialRepresentation = new CredentialRepresentation();
        credentialRepresentation.setTemporary(false);
        credentialRepresentation.setType(CredentialRepresentation.PASSWORD);
        credentialRepresentation.setValue(KEYCLOAK_PASSWORD);
        user.resetPassword(credentialRepresentation);

        RoleRepresentation role = realm.clients().get(client.getId()).roles().get("ADMIN").toRepresentation();
        user.roles().clientLevel(client.getId()).add(Collections.singletonList(role));

        keycloakClientSecret = realm.clients().get(client.getId()).getSecret().getValue();
    }

    private static void setAuthHeader() {
        WebClient webClient = WebClient.builder()
                .baseUrl(KEYCLOAK_CONTAINER.getAuthServerUrl() + "realms/test-realm/protocol/openid-connect")
                .build();

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.put("grant_type", List.of("password"));
        body.put("client_id", List.of("demo"));
        body.put("client_secret", List.of(keycloakClientSecret));
        body.put("username", List.of(KEYCLOAK_USERNAME));
        body.put("password", List.of(KEYCLOAK_PASSWORD));

        Map<String, String> response = webClient.post()
                .uri("/token")
                .body(BodyInserters.fromFormData(body))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, String>>() {})
                .block();

        assert response != null;
        authorizationHeader = response.get("token_type") + " " + response.get("access_token");
    }

    private static void setupMongodb() throws IOException, InterruptedException {
        MONGO_DB_CONTAINER.execInContainer("mongoimport", "--db", "test" ,"--collection", "persons", "--file", "data/persons.json", "--jsonArray");
    }

}
