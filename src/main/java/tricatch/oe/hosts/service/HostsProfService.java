package tricatch.oe.hosts.service;

import org.apache.ibatis.session.SqlSessionFactory;
import tricatch.oe.hosts.mapper.HostsProfMapper;
import tricatch.oe.hosts.model.HostsProf;
import tricatch.oe.hub.i18n.LocaleContext;
import tricatch.oe.hub.mapper.HubUserMapper;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class HostsProfService {

    private static final String EXAMPLE_EN = loadResource("/example/hosts_example_en.txt", "127.0.0.1 foo.oe\n");
    private static final String EXAMPLE_KO = loadResource("/example/hosts_example_ko.txt", "127.0.0.1 foo.oe\n");

    private static String loadResource(String path, String fallback) {
        try (var in = HostsProfService.class.getResourceAsStream(path)) {
            if (in == null) return fallback;
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String exampleContent() {
        var example = "ko".equals(LocaleContext.get()) ? EXAMPLE_KO : EXAMPLE_EN;
        // The sample shows the ${PROXY_SVR} placeholder (oeProxy's address), which means nothing in
        // workspace mode - it has no oeProxy - so that line is left out there.
        if (tricatch.oe.hub.config.AppHome.isWorkspaceMode()) {
            return example.lines()
                .filter(line -> !line.contains("${PROXY_SVR}"))
                .collect(Collectors.joining("\n", "", "\n"));
        }
        return example;
    }

    private final SqlSessionFactory sqlSessionFactory;

    public HostsProfService(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    public List<HostsProf> list(Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            return session.getMapper(HostsProfMapper.class).findByUserNo(userNo);
        }
    }

    public HostsProf get(String hostId) {
        try (var session = sqlSessionFactory.openSession()) {
            return session.getMapper(HostsProfMapper.class).findByHostsId(hostId);
        }
    }

    public HostsProf create(Long userNo) {
        return create(userNo, null, null);
    }

    // encryptedContent/wrappedContentKey come from the client (workspace mode only - e2eEncryption
    // design doc §1): it generates a DEK, encrypts the example content with it, and wraps the DEK
    // with the workspace key (new profiles default to 'public' visibility, same as below).
    // Both null means self-hosted/plaintext, unchanged from before this wiring.
    public HostsProf create(Long userNo, String encryptedContent, String wrappedContentKey) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = new HostsProf();
            hosts.setHostsId(newId());
            hosts.setUserNo(userNo);
            hosts.setHostsProfile(nextUniqueName(existingNames(mapper, userNo), "new hosts"));
            hosts.setHostsContent(encryptedContent != null ? encryptedContent : exampleContent());
            hosts.setWrappedContentKey(wrappedContentKey);
            hosts.setSelected(false);
            var now = LocalDateTime.now();
            hosts.setCreatedBy(userNo);
            hosts.setUpdatedBy(userNo);
            hosts.setCreateAt(now);
            hosts.setUpdatedAt(now);
            hosts.setVisibility("public");
            mapper.insertWithAutoSortOrder(hosts);
            session.commit();
            return mapper.findByHostsId(hosts.getHostsId());
        }
    }

    // linkContent (workspace mode only) is the caller's fresh re-encryption of the same content with
    // the row's existing link DEK (unwrapped client-side via wrappedLinkKey) - the "living link"
    // sync (e2eEncryption design doc §6). Null means either self-hosted or no live link to refresh;
    // never mints a new link (syncLinkContent's own wrapped_link_key IS NOT NULL guard enforces
    // that server-side too). Collabo reference rows (parentId != null) never carry a link of their
    // own, so linkContent is simply ignored on that branch.
    public HostsProf updateContent(String hostId, Long userNo, String content, String linkContent) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var record = mapper.findByHostsId(hostId);
            if (record == null || !record.getUserNo().equals(userNo) || record.getUserNo() < 0) return null;
            var now = LocalDateTime.now();
            if (record.getParentId() != null) {
                mapper.updateContentByParentId(record.getParentId(), userNo, content, now);
            } else {
                mapper.updateContent(hostId, userNo, content, now);
                if (linkContent != null) {
                    var caller = session.getMapper(HubUserMapper.class).findByUserNo(userNo);
                    if (caller != null) {
                        mapper.syncLinkContent(hostId, caller.getWsNo(), linkContent);
                    }
                }
            }
            session.commit();
            return mapper.findByHostsId(hostId);
        }
    }

    // Only used right after create()/copyProfile() when the client couldn't produce the
    // encrypted content in the same request (e.g. create()'s server-generated example content -
    // the client doesn't know that text in advance to encrypt it before asking for the row to
    // exist). Always the simple, non-parent case - a brand-new row never has a parent yet.
    public HostsProf updateContentAndKey(String hostId, Long userNo, String content, String wrappedContentKey) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var record = mapper.findByHostsId(hostId);
            if (record == null || !record.getUserNo().equals(userNo) || record.getUserNo() < 0 || record.getParentId() != null) return null;
            mapper.updateContentAndKey(hostId, userNo, content, wrappedContentKey, LocalDateTime.now());
            session.commit();
            return mapper.findByHostsId(hostId);
        }
    }

    public HostsProf updateProfile(String hostId, Long userNo, String newName) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var record = mapper.findByHostsId(hostId);
            if (record == null || !record.getUserNo().equals(userNo) || record.getUserNo() < 0 || record.getParentId() != null) return null;
            mapper.updateProfile(hostId, userNo, newName, LocalDateTime.now());
            session.commit();
            return mapper.findByHostsId(hostId);
        }
    }

    public HostsProf toggleSelected(String hostId, Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = mapper.findByHostsId(hostId);
            if (hosts == null || !hosts.getUserNo().equals(userNo) || hosts.getUserNo() < 0) return null;
            mapper.updateSelected(hostId, userNo, !hosts.isSelected(), LocalDateTime.now());
            session.commit();
            return mapper.findByHostsId(hostId);
        }
    }

    public void reorder(Long userNo, List<String> hostIds) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            for (int i = 0; i < hostIds.size(); i++) {
                mapper.updateSortOrder(hostIds.get(i), userNo, i);
            }
            session.commit();
        }
    }

    public void delete(String hostId, Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var record = mapper.findByHostsId(hostId);
            if (record == null || !record.getUserNo().equals(userNo) || record.getUserNo() < 0) return;
            var parentId = record.getParentId();
            mapper.delete(hostId, userNo);
            if (parentId != null && mapper.countReferencesByParentId(parentId) == 0) {
                mapper.deleteByHostsId(parentId);
            }
            session.commit();
        }
    }

    /**
     * Removes everything the user owns because they asked for it ("delete all my profiles"): every
     * profile goes, 'public' ones included - the same as deleting each one by hand, or a
     * replace-import. Account removal is different, see {@link #deleteAllForAccountRemoval}.
     */
    public void deleteAll(Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            removeAllRows(session.getMapper(HostsProfMapper.class), userNo);
            session.commit();
        }
    }

    /**
     * Removes a user's profiles because their ACCOUNT is being deleted. 'public' profiles are
     * reassigned to the workspace's wss account rather than deleted with the account -
     * 'private'/'collabo' still go away (cloudGroupService design doc §2.5 orphan handling), so
     * shared resources the team depends on outlive whoever made them. Must not be used for a
     * user-requested "delete all": that must really delete.
     */
    public void deleteAllForAccountRemoval(Long userNo) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);

            // Must run before removeAllRows, which would otherwise delete these too.
            var deletedUser = session.getMapper(HubUserMapper.class).findByUserNo(userNo);
            if (deletedUser != null) {
                var wsSystem = session.getMapper(HubUserMapper.class).findWsSystemByWsNo(deletedUser.getWsNo());
                if (wsSystem != null) {
                    var publicProfiles = mapper.findPublicByUserNo(userNo);
                    var takenNames = existingNames(mapper, wsSystem.getUserNo());
                    var now = LocalDateTime.now();
                    for (var p : publicProfiles) {
                        var name = nextUniqueName(takenNames, p.getHostsProfile());
                        takenNames.add(name.toLowerCase());
                        mapper.reassignOwner(p.getHostsId(), wsSystem.getUserNo(), name, now);
                    }
                }
            }

            removeAllRows(mapper, userNo);
            session.commit();
        }
    }

    // Deletes every row the user owns, then any shared (collabo) parent left with no references.
    private void removeAllRows(HostsProfMapper mapper, Long userNo) {
        var refs = mapper.findReferencesByUserNo(userNo);
        mapper.deleteByUserNo(userNo);
        for (var ref : refs) {
            if (mapper.countReferencesByParentId(ref.getParentId()) == 0) {
                mapper.deleteByHostsId(ref.getParentId());
            }
        }
        mapper.deleteOrphanedParentsByCreator(userNo);
    }

    public HostsProf copyProfile(Long userNo, String sourceHostId) {
        return copyProfile(userNo, sourceHostId, null, null);
    }

    // encryptedContent/wrappedContentKey (workspace mode only): the copy always becomes 'public', so
    // if the source was 'private' the client must unwrap-then-rewrap for the workspace key
    // itself (a plain copy of source's wrap would be wrong) - simplest for the client to just
    // generate a fresh DEK for the copy either way, same as a new create().
    public HostsProf copyProfile(Long userNo, String sourceHostId, String encryptedContent, String wrappedContentKey) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var source = mapper.findByHostsId(sourceHostId);
            if (source == null) return null;
            if (!userNo.equals(source.getUserNo()) && "private".equals(source.getVisibility())) return null;
            // Refuse rather than silently create an undecryptable row: if the source is
            // encrypted (has its own key) and the caller didn't provide a fresh wrap, copying
            // source's ciphertext verbatim with no key would permanently strand that content.
            // hosts.pebble's search-copy button now always supplies one for an encrypted source
            // (unwraps+decrypts via GET /api/hosts/{id}/view, then re-encrypts with a fresh DEK
            // - e2eEncryption design doc §9), so this guard is now purely defense in depth.
            // Plaintext sources (self-hosted) are unaffected and still copy exactly as before.
            if (source.getWrappedContentKey() != null && wrappedContentKey == null) return null;
            var copy = new HostsProf();
            copy.setHostsId(newId());
            copy.setUserNo(userNo);
            copy.setHostsProfile(nextUniqueName(existingNames(mapper, userNo), source.getHostsProfile()));
            copy.setHostsContent(encryptedContent != null ? encryptedContent : source.getHostsContent());
            copy.setWrappedContentKey(wrappedContentKey);
            copy.setSelected(false);
            var now = LocalDateTime.now();
            copy.setCreatedBy(userNo);
            copy.setUpdatedBy(userNo);
            copy.setCreateAt(now);
            copy.setUpdatedAt(now);
            copy.setVisibility("public");
            mapper.insertWithAutoSortOrder(copy);
            session.commit();
            return mapper.findByHostsId(copy.getHostsId());
        }
    }

    public HostsProf registerCollabo(Long userNo, String parentId) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var parent = mapper.findByHostsId(parentId);
            if (parent == null || parent.getUserNo() >= 0 || !"collabo".equals(parent.getVisibility())) return null;
            // A collabo target must be in the same workspace as its owner - searchOthers() already
            // won't surface a cross-workspace item, but this call takes parentId directly, so a
            // guessed/leaked hosts_id must still be rejected here (cloudGroupService design doc
            // §2.4 "collabo 대상 검증"). No-op check in self-hosted (exactly one workspace).
            var userMapper = session.getMapper(HubUserMapper.class);
            var registrant = userMapper.findByUserNo(userNo);
            var owner = userMapper.findByUserNo(-parent.getUserNo());
            if (registrant == null || owner == null || !registrant.getWsNo().equals(owner.getWsNo())) return null;
            var existing = mapper.findReferencesByUserNo(userNo);
            if (existing.stream().anyMatch(r -> parentId.equals(r.getParentId()))) return null;
            var ref = new HostsProf();
            ref.setHostsId(newId());
            ref.setUserNo(userNo);
            ref.setHostsProfile(nextUniqueName(existingNames(mapper, userNo), parent.getHostsProfile()));
            ref.setHostsContent("");
            ref.setSelected(false);
            var now = LocalDateTime.now();
            ref.setCreatedBy(userNo);
            ref.setUpdatedBy(userNo);
            ref.setCreateAt(now);
            ref.setUpdatedAt(now);
            ref.setVisibility("collabo");
            ref.setParentId(parentId);
            mapper.insertWithAutoSortOrder(ref);
            session.commit();
            return mapper.findByHostsId(ref.getHostsId());
        }
    }

    // wrappedContentKey is the client's re-wrap of the row's existing DEK for the KEK that the
    // target visibility implies (personal key for 'private', workspace key for 'collabo'/
    // 'public') - the DEK itself never changes on a visibility flip (e2eEncryption design doc
    // §7). Null in self-hosted, where content/keys are never encrypted (design doc §1).
    public HostsProf updateVisibility(String hostId, Long userNo, String visibility, String wrappedContentKey) {
        if ("collabo".equals(visibility)) {
            return convertToCollabo(hostId, userNo, wrappedContentKey);
        }
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = mapper.findByHostsId(hostId);
            if (hosts == null || !hosts.getUserNo().equals(userNo) || hosts.getUserNo() < 0) return null;
            if ("collabo".equals(hosts.getVisibility())) return null;
            // Refuse rather than silently strand ciphertext with a stale/missing key - see the
            // identical guard in copyProfile.
            if (hosts.getWrappedContentKey() != null && wrappedContentKey == null) return null;
            mapper.updateVisibility(hostId, userNo, visibility, wrappedContentKey, LocalDateTime.now());
            // Auto-revoke any live public link the moment visibility leaves 'public' (e2eEncryption
            // design doc §6) - previously the "Generate"/"Revoke" button was merely disabled here
            // while an already-issued link stayed live and reachable at its URL.
            if (!"public".equals(visibility)) {
                mapper.clearLink(hostId);
            }
            session.commit();
            return mapper.findByHostsId(hostId);
        }
    }

    private HostsProf convertToCollabo(String hostId, Long userNo, String wrappedContentKey) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            var hosts = mapper.findByHostsId(hostId);
            if (hosts == null || !hosts.getUserNo().equals(userNo) || hosts.getUserNo() < 0) return null;
            if (hosts.getParentId() != null) return null;
            // Same guard as updateVisibility/copyProfile - never move ciphertext to the new
            // parent row without a key to go with it.
            if (hosts.getWrappedContentKey() != null && wrappedContentKey == null) return null;
            var now = LocalDateTime.now();
            var parent = new HostsProf();
            parent.setHostsId(newId());
            parent.setUserNo(-userNo);
            parent.setHostsProfile(hosts.getHostsProfile());
            parent.setHostsContent(hosts.getHostsContent());
            parent.setWrappedContentKey(wrappedContentKey);
            parent.setSelected(false);
            parent.setSortOrder(0);
            // The real actor is always the positive userNo, even though ownership (user_no) is
            // stored negative for this row — created_by/updated_by must never carry that sign
            // convention, or resolving them back to a HUB_USR row would fail.
            parent.setCreatedBy(userNo);
            parent.setUpdatedBy(userNo);
            parent.setCreateAt(now);
            parent.setUpdatedAt(now);
            parent.setVisibility("collabo");
            mapper.insert(parent);
            mapper.setAsCollaboRef(hostId, userNo, parent.getHostsId(), now);
            // Auto-revoke (design doc §6): 'collabo' is a restricted audience, defeating the whole
            // point of a fully-public no-login link - same reasoning as updateVisibility's
            // downgrade to 'private' above.
            mapper.clearLink(hostId);
            session.commit();
            return mapper.findByHostsId(hostId);
        }
    }

    public List<HostsProf> searchOthers(Long userNo, String keyword) {
        try (var session = sqlSessionFactory.openSession()) {
            var wsNo = session.getMapper(HubUserMapper.class).findByUserNo(userNo).getWsNo();
            return session.getMapper(HostsProfMapper.class).searchOthers(userNo, wsNo, keyword);
        }
    }

    public List<HostsProf> importProfiles(Long userNo, List<HostsProf> entries, boolean merge) {
        try (var session = sqlSessionFactory.openSession()) {
            var mapper = session.getMapper(HostsProfMapper.class);
            if (!merge) removeAllRows(mapper, userNo);
            var existingNames = existingNames(mapper, userNo);
            for (var entry : entries) {
                entry.setHostsId(newId());
                entry.setUserNo(userNo);
                var name = nextUniqueName(existingNames, entry.getHostsProfile());
                entry.setHostsProfile(name);
                existingNames.add(name.toLowerCase());
                var now = LocalDateTime.now();
                entry.setCreatedBy(userNo);
                entry.setUpdatedBy(userNo);
                entry.setCreateAt(now);
                entry.setUpdatedAt(now);
                if (entry.getVisibility() == null) entry.setVisibility("private");
                mapper.insert(entry);
            }
            session.commit();
            return mapper.findByUserNo(userNo);
        }
    }

    /**
     * The rows described by an import/restore file's list of profiles. The whole list is validated
     * before anything is written, so a bad entry can't leave a replace-import half applied.
     *
     * @throws IllegalArgumentException (message safe to show) if the list or an entry is malformed
     */
    public static List<HostsProf> entriesFromImport(Object raw) {
        var entries = new java.util.ArrayList<HostsProf>();
        for (var m : tricatch.oe.hub.util.ImportFields.maps(raw, "hosts")) {
            var h = new HostsProf();
            h.setHostsProfile(tricatch.oe.hub.util.ImportFields.requiredText(m, "hostsProfile"));
            h.setHostsContent(tricatch.oe.hub.util.ImportFields.text(m, "hostsContent", ""));
            // Round-tripping the same account's own export/backup back in: it serializes the row's
            // wrapped_content_key verbatim, and it stays valid here unchanged - re-import never touches
            // the DEK or which key wraps it (collabo/public share the identical workspace-key wrap,
            // e2eEncryption design doc §6/§9). Without this, an encrypted row's hostsContent
            // (ciphertext) would land with no key at all and be shown as if it were plaintext.
            h.setWrappedContentKey(tricatch.oe.hub.util.ImportFields.text(m, "wrappedContentKey", null));
            h.setSelected(Boolean.TRUE.equals(m.get("selected")));
            h.setSortOrder(tricatch.oe.hub.util.ImportFields.intValue(m, "sortOrder", 0));
            // Import creates standalone entries, never collabo refs, and a file that doesn't say
            // "public" must not make the row public - see VisibilityUtil.forImport.
            h.setVisibility(tricatch.oe.hub.util.VisibilityUtil.forImport(m.get("visibility")));
            entries.add(h);
        }
        return entries;
    }

    public String getOwnerUserId(String hostId) {
        try (var session = sqlSessionFactory.openSession()) {
            var hostsProf = session.getMapper(HostsProfMapper.class).findByHostsId(hostId);
            if (hostsProf == null) return null;
            var hubUser = session.getMapper(HubUserMapper.class).findByUserNo(hostsProf.getUserNo());
            return hubUser != null ? hubUser.getUserId() : null;
        }
    }

    // Used by the /share viewer's workspace-mode workspace gate (e2eEncryption design doc §9's
    // reinterpretation of cloudGroupService doc §2.4: a shared link is only viewable by someone
    // already logged into the SAME workspace, since that's what lets their browser use its
    // cached workspace key to decrypt). Mirrors getOwnerUserId's exact lookup (hostsProf.getUserNo()
    // un-negated - a share link is always minted from the viewer's own row, never a raw collabo
    // parent id, so this is always a real, positive HUB_USR.user_no).
    public Long getOwnerWsNo(String hostId) {
        try (var session = sqlSessionFactory.openSession()) {
            var hostsProf = session.getMapper(HostsProfMapper.class).findByHostsId(hostId);
            if (hostsProf == null) return null;
            var hubUser = session.getMapper(HubUserMapper.class).findByUserNo(hostsProf.getUserNo());
            return hubUser != null ? hubUser.getWsNo() : null;
        }
    }

    // Fully-public, no-login link (e2eEncryption design doc §6 "living link" redesign): linkContent
    // is already-encrypted ciphertext and wrappedLinkKey is that same DEK wrapped with the caller's
    // workspace key, so any workspace member's browser can re-encrypt on later saves - the raw key
    // itself still only ever appears in the share URL's fragment. Both null revokes; exactly one
    // null is rejected (a link is issued/revoked as a pair, never half-updated) by returning null,
    // same signal as "not found"/"not public" below - the controller maps either to an error
    // response. 'public' only - private is pointless here and collabo's restricted audience
    // defeats the point of "anyone, no login".
    public HostsProf setPublicLink(String hostId, Long callerUserNo, String linkContent, String wrappedLinkKey) {
        if ((linkContent == null) != (wrappedLinkKey == null)) return null;
        try (var session = sqlSessionFactory.openSession()) {
            var hostsMapper = session.getMapper(HostsProfMapper.class);
            var hosts = hostsMapper.findByHostsId(hostId);
            if (hosts == null || !"public".equals(hosts.getVisibility())) return null;
            var caller = session.getMapper(HubUserMapper.class).findByUserNo(callerUserNo);
            if (caller == null) return null;
            hostsMapper.updateLinkContent(hostId, caller.getWsNo(), linkContent, wrappedLinkKey);
            session.commit();
            return hostsMapper.findByHostsId(hostId);
        }
    }

    // Public, unauthenticated read for the /link viewer - visibility='public' and a non-null
    // link_content (an issued, not-yet-revoked link) are the only gates; no workspace/login check
    // at all, since that's the entire point of this sharing mode (§6).
    public HostsProf getForPublicLink(String hostId) {
        var hosts = get(hostId);
        if (hosts == null || !"public".equals(hosts.getVisibility()) || hosts.getLinkContent() == null) return null;
        return hosts;
    }

    // Case-insensitive, matching countByUserNoAndProfile's LOWER() comparison this replaces.
    private Set<String> existingNames(HostsProfMapper mapper, Long userNo) {
        return mapper.findProfileNamesByUserNo(userNo).stream()
            .map(String::toLowerCase)
            .collect(Collectors.toCollection(HashSet::new));
    }

    private String nextUniqueName(Set<String> existingNamesLower, String base) {
        if (!existingNamesLower.contains(base.toLowerCase())) return base;
        for (int i = 2; i <= 999; i++) {
            var candidate = base + " (" + i + ")";
            if (!existingNamesLower.contains(candidate.toLowerCase())) return candidate;
        }
        return base + " (" + System.currentTimeMillis() + ")";
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
