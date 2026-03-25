package run.halo.app.core.endpoint.console;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.content.Builder.contentBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static org.springdoc.core.fn.builders.schema.Builder.schemaBuilder;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.core.user.service.EmailPasswordRecoveryService;
import run.halo.app.core.user.service.InvalidResetTokenException;
import run.halo.app.extension.GroupVersion;

/**
 * Public REST API endpoint for email-based password reset.
 *
 * <p>Provides two operations:
 * <ul>
 *   <li>{@code POST /users/-/send-password-reset-email} — request a reset link by email</li>
 *   <li>{@code PUT /users/{username}/reset-password} — apply a reset token</li>
 * </ul>
 *
 * <p>Both operations are accessible without authentication (anonymous role covers
 * {@code api.halo.run} resources).
 *
 * @author copilot
 * @since 2.23.0
 */
@Component
@RequiredArgsConstructor
class UserPasswordResetEndpoint implements CustomEndpoint {

    private final EmailPasswordRecoveryService emailService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        var tag = "UserV1alpha1Public";
        return SpringdocRouteBuilder.route()
            .POST("users/-/send-password-reset-email", this::sendPasswordResetEmail,
                ops -> ops.operationId("SendPasswordResetEmail")
                    .tag(tag)
                    .description(
                        "Send a password-reset email. Returns 204 whether or not the "
                            + "user/email combination exists, to prevent user enumeration.")
                    .requestBody(requestBodyBuilder()
                        .required(true)
                        .content(contentBuilder()
                            .mediaType(MediaType.APPLICATION_JSON_VALUE)
                            .schema(schemaBuilder()
                                .implementation(SendPasswordResetEmailRequest.class))
                        ))
                    .response(responseBuilder()
                        .responseCode("204")
                        .description("Reset email dispatched (or silently ignored)."))
            )
            .PUT("users/{username}/reset-password", this::resetPassword,
                ops -> ops.operationId("ResetPasswordByToken")
                    .tag(tag)
                    .description("Reset password using a one-time token.")
                    .requestBody(requestBodyBuilder()
                        .required(true)
                        .content(contentBuilder()
                            .mediaType(MediaType.APPLICATION_JSON_VALUE)
                            .schema(schemaBuilder()
                                .implementation(ResetPasswordRequest.class))
                        ))
                    .response(responseBuilder()
                        .responseCode("204")
                        .description("Password changed successfully."))
                    .response(responseBuilder()
                        .responseCode("403")
                        .description("Token is invalid or expired."))
            )
            .build();
    }

    Mono<ServerResponse> sendPasswordResetEmail(ServerRequest request) {
        return request.bodyToMono(SendPasswordResetEmailRequest.class)
            .flatMap(body ->
                emailService.sendPasswordResetEmail(body.getUsername(), body.getEmail()))
            .then(ServerResponse.noContent().build());
    }

    Mono<ServerResponse> resetPassword(ServerRequest request) {
        return request.bodyToMono(ResetPasswordRequest.class)
            .flatMap(body ->
                emailService.changePassword(body.getNewPassword(), body.getToken()))
            .then(ServerResponse.noContent().build())
            .onErrorResume(InvalidResetTokenException.class,
                e -> ServerResponse.status(HttpStatus.FORBIDDEN).build());
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.halo.run", "v1alpha1");
    }

    @Data
    static class SendPasswordResetEmailRequest {
        private String username;
        private String email;
    }

    @Data
    static class ResetPasswordRequest {
        private String newPassword;
        private String token;
    }

}
