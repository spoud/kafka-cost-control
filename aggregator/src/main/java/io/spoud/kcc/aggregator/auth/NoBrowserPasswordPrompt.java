package io.spoud.kcc.aggregator.auth;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;

/**
 * Keeps the browser's own password prompt away from the UI. A 401 with
 * {@code WWW-Authenticate: basic} makes the browser prompt even for a script's request, and while
 * the prompt is open every request to the host hangs - a wrong password on the sign-in page froze
 * the app. Requests the UI marks with {@code X-Requested-With} get the 401 without the header and
 * show their own sign-in; scripts still get the challenge.
 */
public class NoBrowserPasswordPrompt {

    static final String REQUESTED_WITH = "X-Requested-With";

    @RouteFilter(Integer.MAX_VALUE - 1)
    void dropBasicChallengeForTheUi(RoutingContext context) {
        if (context.request().getHeader(REQUESTED_WITH) != null) {
            context.addHeadersEndHandler(v -> {
                if (context.response().getStatusCode() == 401) {
                    context.response().headers().remove("WWW-Authenticate");
                }
            });
        }
        context.next();
    }
}
