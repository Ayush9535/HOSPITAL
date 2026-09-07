package com.hms.service;

import com.hms.security.HospitalWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * One place to push a live update to a tenant's open tabs.
 *
 * Every caller of {@link HospitalWebSocketHandler} used to hand-roll the same three things: the
 * JSON literal, a try/catch so a socket problem can never fail the save, and (usually not) any
 * thought about transaction timing. That is ~60 near-identical blocks, and the omissions are what
 * produced the stale-UI bugs: a service that simply forgot to broadcast looks exactly like one
 * that chose not to.
 *
 * Two rules are baked in here so no call site has to remember them:
 *
 *  1. BEST-EFFORT. A failed push must never roll back or fail the write that triggered it. The
 *     data is saved; the worst case is a client that refreshes manually.
 *
 *  2. AFTER COMMIT. When a transaction is open, the push is deferred until it commits. Pushing
 *     from inside the transaction lets a client re-fetch and read the PRE-change row (the writer's
 *     changes aren't visible to other connections yet) and cache that stale value — which is the
 *     exact staleness the push exists to prevent. Outside a transaction it fires immediately.
 */
@Component
public class RealtimeNotifier {

    private static final Logger logger = LoggerFactory.getLogger(RealtimeNotifier.class);

    private static final String REFRESH_DATA = "{\"type\":\"REFRESH_DATA\"}";
    private static final String SETTINGS_UPDATED = "{\"type\":\"SETTINGS_UPDATED\"}";

    @Autowired
    private HospitalWebSocketHandler webSocketHandler;

    /** "Your lists changed — reload them." The workhorse: any create/update/delete of tenant data. */
    public void refresh(Long hospitalId) {
        send(hospitalId, REFRESH_DATA);
    }

    /**
     * "Who you are, or what your plan/settings allow, changed — re-read your profile."
     * Clients respond by re-fetching /auth/me, so modules and settings take effect without a login.
     */
    public void settingsUpdated(Long hospitalId) {
        send(hospitalId, SETTINGS_UPDATED);
    }

    /** A settings change that also alters the data on screen: re-read the profile AND the lists. */
    public void settingsAndData(Long hospitalId) {
        send(hospitalId, SETTINGS_UPDATED);
        send(hospitalId, REFRESH_DATA);
    }

    /**
     * "Reload your lists" to every connected tenant. For platform-owned content that all tenants
     * read but none owns (FAQs). Prefer {@link #refresh(Long)} whenever the change belongs to one
     * tenant -- this one touches everybody.
     */
    public void refreshAllTenants() {
        afterCommit(() -> {
            try {
                webSocketHandler.broadcastToAllTenants(REFRESH_DATA);
            } catch (Exception e) {
                logger.warn("Failed to broadcast refresh to all tenants", e);
            }
        });
    }

    /** Push to the Super Admin channel (reserved hospitalId 0). */
    public void platform(String jsonPayload) {
        afterCommit(() -> {
            try {
                webSocketHandler.broadcastToPlatform(jsonPayload);
            } catch (Exception e) {
                logger.warn("Failed to broadcast to the platform channel", e);
            }
        });
    }

    private void send(Long hospitalId, String payload) {
        if (hospitalId == null) return;
        afterCommit(() -> {
            try {
                webSocketHandler.broadcast(hospitalId, payload);
            } catch (Exception e) {
                logger.warn("Failed to broadcast {} to hospital {}", payload, hospitalId, e);
            }
        });
    }

    private void afterCommit(Runnable push) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    push.run();
                }
            });
        } else {
            push.run();
        }
    }
}
