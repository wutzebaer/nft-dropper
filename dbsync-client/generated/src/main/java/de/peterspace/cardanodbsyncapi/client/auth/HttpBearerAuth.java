package de.peterspace.cardanodbsyncapi.client.auth;

import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;

@jakarta.annotation.Generated(value = "org.openapitools.codegen.languages.JavaClientCodegen", date = "2023-09-25T16:31:10.010729500+02:00[Europe/Berlin]")
public class HttpBearerAuth implements Authentication {
    private final String scheme;
    private Supplier<String> tokenSupplier;

    public HttpBearerAuth(String scheme) {
        this.scheme = scheme;
    }

    public String getBearerToken() {
        return tokenSupplier.get();
    }

    public void setBearerToken(String bearerToken) {
        this.tokenSupplier = () -> bearerToken;
    }
    
    public void setBearerToken(Supplier<String> tokenSupplier) {
        this.tokenSupplier = tokenSupplier;
    }

    @Override
    public void applyToParams(MultiValueMap<String, String> queryParams, HttpHeaders headerParams, MultiValueMap<String, String> cookieParams) {
        String bearerToken = Optional.ofNullable(tokenSupplier).map(Supplier::get).orElse(null);
        if (bearerToken == null) {
            return;
        }
        headerParams.add(HttpHeaders.AUTHORIZATION, (scheme != null ? upperCaseBearer(scheme) + " " : "") + bearerToken);
    }

    private static String upperCaseBearer(String scheme) {
        return ("bearer".equalsIgnoreCase(scheme)) ? "Bearer" : scheme;
    }
}
