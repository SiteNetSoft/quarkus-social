package org.sitenetsoft.quarkus.social.atproto;

import java.time.Duration;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.atproto.syntax.RecordKey;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoSessionExpired;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoSessions;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.xrpc.AtprotoRecords;
import org.sitenetsoft.quarkus.atproto.xrpc.RecordResult;
import org.sitenetsoft.quarkus.atproto.xrpc.api.ReactiveXrpcClient;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcException;

import com.atproto.repo.GetRecord;
import com.atproto.repo.RepoClient;
import com.atproto.repo.StrongRef;

/**
 * Blocking access to a linked account's repository (call on a worker thread): the records API of its session, and a
 * create that treats an existing record at the same key as written by an earlier attempt.
 */
@ApplicationScoped
public class AtprotoRepo {

    private static final Logger LOG = Logger.getLogger(AtprotoRepo.class);
    private static final Duration READ_LIMIT = Duration.ofSeconds(30);

    @Inject
    AtprotoSessions sessions;
    @Inject
    ReactiveXrpcClient xrpc;

    /** A record the call wrote ({@code created}) or found already there. */
    record Written(StrongRef ref, boolean created) {
    }

    /** @throws AtprotoSessionExpired when the account has no live session */
    AtprotoRecords records(Did did) {
        return sessions.records(did);
    }

    /**
     * {@code createRecord} at {@code rkey}. When the PDS refuses it with a client error, the record may already be
     * there (an earlier attempt whose answer was lost; PDSs name that error differently): it is read back, and if
     * present reported as written. Otherwise the refusal is rethrown.
     */
    Written createOrFind(AtprotoRecords records, Did did, Nsid collection, RecordKey rkey, Object record) {
        try {
            RecordResult r = records.create(collection, rkey, record);
            return new Written(new StrongRef(r.uri(), r.cid()), true);
        } catch (XrpcException e) {
            if (e.status() < 400 || e.status() >= 500 || e.status() == 401 || e.status() == 403 || e.status() == 429) {
                throw e;
            }
            Optional<StrongRef> existing = current(did, collection, rkey);
            if (existing.isEmpty()) {
                throw e;
            }
            LOG.debugf("%s/%s/%s already exists (%s); an earlier attempt wrote it", did, collection, rkey,
                    e.getMessage());
            return new Written(existing.get(), false);
        }
    }

    /** The record's uri and current cid ({@code getRecord}, which needs no auth), empty when there is none. */
    Optional<StrongRef> current(Did did, Nsid collection, RecordKey rkey) {
        AtprotoSession session = sessions.find(did)
                .orElseThrow(() -> new AtprotoSessionExpired("No AT Protocol session for " + did));
        try {
            GetRecord.Output out = new RepoClient(xrpc)
                    .getRecord(session.pds(), new GetRecord.Params(did, collection, rkey, null), Optional.empty())
                    .await().atMost(READ_LIMIT);
            return out.cid() == null ? Optional.empty() : Optional.of(new StrongRef(out.uri(), out.cid()));
        } catch (XrpcException e) {
            if (GetRecord.ERROR_RECORD_NOT_FOUND.equals(e.error())) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
