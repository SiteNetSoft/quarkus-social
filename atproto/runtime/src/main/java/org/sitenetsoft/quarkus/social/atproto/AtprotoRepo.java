package org.sitenetsoft.quarkus.social.atproto;

import java.time.Duration;
import java.util.List;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Blocking access to a linked account's repository (call on a worker thread): the records API of its session, and a
 * create that recognises the record an earlier attempt wrote.
 */
@ApplicationScoped
public class AtprotoRepo {

    static final String COLLISION = "record key collision";

    private static final Logger LOG = Logger.getLogger(AtprotoRepo.class);
    private static final Duration READ_LIMIT = Duration.ofSeconds(30);

    @Inject
    AtprotoSessions sessions;
    @Inject
    ReactiveXrpcClient xrpc;
    @Inject
    ObjectMapper mapper;

    /** A record the call wrote ({@code created}) or found already there. */
    record Written(StrongRef ref, boolean created) {
    }

    /** A record at a key, with its current value. */
    private record Found(StrongRef ref, JsonNode value) {
    }

    /** Every candidate key holds another post's record. */
    static final class KeyCollision extends RuntimeException {
        private static final long serialVersionUID = 1L;

        KeyCollision(Nsid collection, int tried) {
            super(COLLISION + ": the " + tried + " " + collection + " keys derived from this post all hold other "
                    + "records");
        }
    }

    /** @throws AtprotoSessionExpired when the account has no live session */
    AtprotoRecords records(Did did) {
        return sessions.records(did);
    }

    /**
     * {@code createRecord} at the first of {@code keys} that is free or already holds this post's record (an earlier
     * attempt whose answer was lost). A key "holds this post's record" when the stored value equals {@code record} at
     * every one of {@code identity} (JSON pointers of the fields that are fixed for the post).
     *
     * @throws KeyCollision when every key holds another record
     */
    Written createOrFind(AtprotoRecords records, Did did, Nsid collection, List<RecordKey> keys, Object record,
            List<String> identity) {
        JsonNode expected = mapper.valueToTree(record);
        for (RecordKey key : keys) {
            try {
                RecordResult r = records.create(collection, key, record);
                return new Written(new StrongRef(r.uri(), r.cid()), true);
            } catch (XrpcException e) {
                // How a real PDS names "this key is taken" is unverified (the fake answers 400 InvalidRequest; it is
                // to be checked live), and a 5xx may hide a create that went through: on any error that isn't about
                // authorization or rate limits, read the key back before deciding.
                if (e.status() == 401 || e.status() == 403 || e.status() == 429) {
                    throw e;
                }
                Optional<Found> existing = current(did, collection, key);
                if (existing.isEmpty()) {
                    throw e;
                }
                if (identity.stream().allMatch(p -> expected.at(p).equals(existing.get().value().at(p)))) {
                    LOG.debugf("%s/%s/%s already holds this post's record (%s)", did, collection, key, e.getMessage());
                    return new Written(existing.get().ref(), false);
                }
                LOG.debugf("%s/%s/%s holds another record; trying the next key", did, collection, key);
            }
        }
        throw new KeyCollision(collection, keys.size());
    }

    /** The record at the key ({@code getRecord}, which needs no auth), empty when there is none. */
    private Optional<Found> current(Did did, Nsid collection, RecordKey rkey) {
        AtprotoSession session = sessions.find(did)
                .orElseThrow(() -> new AtprotoSessionExpired("No AT Protocol session for " + did));
        try {
            GetRecord.Output out = new RepoClient(xrpc)
                    .getRecord(session.pds(), new GetRecord.Params(did, collection, rkey, null), Optional.empty())
                    .await().atMost(READ_LIMIT);
            return out.cid() == null ? Optional.empty()
                    : Optional.of(new Found(new StrongRef(out.uri(), out.cid()), out.value()));
        } catch (XrpcException e) {
            if (GetRecord.ERROR_RECORD_NOT_FOUND.equals(e.error())) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
