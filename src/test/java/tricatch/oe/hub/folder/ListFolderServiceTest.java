package tricatch.oe.hub.folder;

import org.junit.jupiter.api.Test;
import tricatch.oe.hosts.model.HostsProf;
import tricatch.oe.hosts.service.HostsProfService;
import tricatch.oe.hub.proxy.service.ProxyVhostService;
import tricatch.oe.hub.util.NameTakenException;
import tricatch.oe.mapper.MapperTestBase;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// One-level sidebar folders: the tree order (folders interleaved with the items outside them),
// ownership checks, what happens to items when their folder or account goes away, and import by
// folder name.
class ListFolderServiceTest extends MapperTestBase {

    private static List<String> names(List<HostsProf> list) {
        return list.stream().map(HostsProf::getHostsProfile).toList();
    }

    private static HostsProf named(HostsProfService service, Long userNo, String name) {
        var p = service.create(userNo);
        return service.updateProfile(p.getHostsId(), userNo, name);
    }

    private static ListFolderService.TreeEntry folder(ListFolder f) {
        return new ListFolderService.TreeEntry(true, f.getFolderId(), null);
    }

    private static ListFolderService.TreeEntry item(String id, ListFolder in) {
        return new ListFolderService.TreeEntry(false, id, in == null ? null : in.getFolderId());
    }

    @Test
    void list_followsTheSavedTree_foldersInterleavedWithRootItems() {
        var user = insertUser("folderOrderUser");
        var hosts = new HostsProfService(FACTORY);
        var folders = new ListFolderService(FACTORY, FolderKind.HOSTS);
        var a = named(hosts, user.getUserNo(), "a");
        var b = named(hosts, user.getUserNo(), "b");
        var c = named(hosts, user.getUserNo(), "c");
        var d = named(hosts, user.getUserNo(), "d");
        var f1 = folders.create(user.getUserNo(), "f1");
        var f2 = folders.create(user.getUserNo(), "f2");

        // a, [f1: c], b, [f2: d, empty otherwise]
        folders.saveTreeOrder(user.getUserNo(), List.of(
            item(a.getHostsId(), null), folder(f1), item(c.getHostsId(), f1),
            item(b.getHostsId(), null), folder(f2), item(d.getHostsId(), f2)));

        var list = hosts.list(user.getUserNo());
        assertThat(names(list)).containsExactly("a", "c", "b", "d");
        assertThat(list.get(0).getFolderId()).isNull();
        assertThat(list.get(1).getFolderId()).isEqualTo(f1.getFolderId());
        assertThat(list.get(1).getFolderName()).isEqualTo("f1");
        assertThat(list.get(3).getFolderName()).isEqualTo("f2");

        // A folder can move ahead of the root items, taking its items with it.
        folders.saveTreeOrder(user.getUserNo(), List.of(
            folder(f2), item(d.getHostsId(), f2), item(a.getHostsId(), null),
            folder(f1), item(c.getHostsId(), f1), item(b.getHostsId(), null)));
        assertThat(names(hosts.list(user.getUserNo()))).containsExactly("d", "a", "c", "b");

        // New entries go after everything, folders included.
        named(hosts, user.getUserNo(), "e");
        var f3 = folders.create(user.getUserNo(), "f3");
        assertThat(names(hosts.list(user.getUserNo()))).containsExactly("d", "a", "c", "b", "e");
        assertThat(f3.getSortOrder()).isGreaterThan(hosts.list(user.getUserNo()).get(4).getSortOrder());
    }

    @Test
    void deletingAFolder_movesItsItemsToTheRoot() {
        var user = insertUser("folderDeleteUser");
        var hosts = new HostsProfService(FACTORY);
        var folders = new ListFolderService(FACTORY, FolderKind.HOSTS);
        var p = named(hosts, user.getUserNo(), "p");
        var f = folders.create(user.getUserNo(), "f");
        folders.saveTreeOrder(user.getUserNo(), List.of(folder(f), item(p.getHostsId(), f)));

        assertThat(folders.delete(user.getUserNo(), f.getFolderId())).isTrue();

        assertThat(hosts.get(p.getHostsId()).getFolderId()).isNull();
        assertThat(folders.list(user.getUserNo())).isEmpty();
    }

    @Test
    void foldersAndItems_areOnlyTheOwnersToUse() {
        var alice = insertUser("folderAlice");
        var bob = insertUser("folderBob");
        var hosts = new HostsProfService(FACTORY);
        var folders = new ListFolderService(FACTORY, FolderKind.HOSTS);
        var alicesItem = named(hosts, alice.getUserNo(), "mine");
        var bobsFolder = folders.create(bob.getUserNo(), "bob's");
        var alicesFolder = folders.create(alice.getUserNo(), "alice's");

        // Alice can't file her item under Bob's folder - the whole save is refused.
        assertThatThrownBy(() -> folders.saveTreeOrder(alice.getUserNo(), List.of(item(alicesItem.getHostsId(), bobsFolder))))
            .isInstanceOf(IllegalArgumentException.class);
        // Bob can't move Alice's item into his folder: rows that aren't the caller's are skipped.
        folders.saveTreeOrder(bob.getUserNo(), List.of(folder(bobsFolder), item(alicesItem.getHostsId(), bobsFolder)));
        assertThat(folders.rename(bob.getUserNo(), alicesFolder.getFolderId(), "taken over")).isNull();
        assertThat(folders.delete(bob.getUserNo(), alicesFolder.getFolderId())).isFalse();
        assertThat(hosts.get(alicesItem.getHostsId()).getFolderId()).isNull();
    }

    @Test
    void names_areUniquePerUser_caseInsensitive() {
        var user = insertUser("folderNameUser");
        var other = insertUser("folderNameOther");
        var folders = new ListFolderService(FACTORY, FolderKind.HOSTS);

        assertThat(folders.create(user.getUserNo(), null).getFolderName()).isEqualTo("new folder");
        assertThat(folders.create(user.getUserNo(), null).getFolderName()).isEqualTo("new folder (2)");

        var work = folders.create(user.getUserNo(), "Work");
        assertThatThrownBy(() -> folders.create(user.getUserNo(), "work")).isInstanceOf(NameTakenException.class);
        var second = folders.create(user.getUserNo(), "second");
        assertThatThrownBy(() -> folders.rename(user.getUserNo(), second.getFolderId(), "WORK")).isInstanceOf(NameTakenException.class);
        // Renaming to its own name in another case is fine, and other users are unaffected.
        assertThat(folders.rename(user.getUserNo(), work.getFolderId(), "WORK").getFolderName()).isEqualTo("WORK");
        assertThat(folders.create(other.getUserNo(), "work")).isNotNull();

        assertThatThrownBy(() -> folders.create(user.getUserNo(), "   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void import_assignsFoldersByName_andAReplaceImportDropsTheOldFolders() {
        var user = insertUser("folderImportUser");
        var hosts = new HostsProfService(FACTORY);
        var folders = new ListFolderService(FACTORY, FolderKind.HOSTS);
        folders.create(user.getUserNo(), "old");

        var entries = HostsProfService.entriesFromImport(List.of(
            java.util.Map.of("hostsProfile", "in-x", "folderName", "x"),
            java.util.Map.of("hostsProfile", "root"),
            java.util.Map.of("hostsProfile", "also-x", "folderName", "x")));
        hosts.importProfiles(user.getUserNo(), entries, false);

        assertThat(folders.list(user.getUserNo())).extracting(ListFolder::getFolderName).containsExactly("x");
        var list = hosts.list(user.getUserNo());
        assertThat(names(list)).containsExactly("in-x", "also-x", "root");
        assertThat(list.get(0).getFolderName()).isEqualTo("x");
        assertThat(list.get(2).getFolderId()).isNull();

        // A merge import reuses an existing folder of the same name instead of making another.
        hosts.importProfiles(user.getUserNo(), HostsProfService.entriesFromImport(List.of(
            java.util.Map.of("hostsProfile", "merged", "folderName", "X"))), true);
        assertThat(folders.list(user.getUserNo())).hasSize(1);
        assertThat(hosts.list(user.getUserNo())).filteredOn(p -> p.getHostsProfile().equals("merged"))
            .extracting(HostsProf::getFolderName).containsExactly("x");
    }

    @Test
    void accountRemoval_removesTheFolders_andReassignedProfilesLandAtWssRoot() {
        var wsSystem = insertWsSystem(TEST_WS_NO);
        var owner = insertUser("folderLeaver");
        var hosts = new HostsProfService(FACTORY);
        var folders = new ListFolderService(FACTORY, FolderKind.HOSTS);
        var shared = hosts.create(owner.getUserNo()); // 'workspace' scope - survives, moves to wss
        var f = folders.create(owner.getUserNo(), "team");
        folders.saveTreeOrder(owner.getUserNo(), List.of(folder(f), item(shared.getHostsId(), f)));

        hosts.deleteAllForAccountRemoval(owner.getUserNo());

        assertThat(folders.list(owner.getUserNo())).isEmpty();
        var survived = hosts.get(shared.getHostsId());
        assertThat(survived.getUserNo()).isEqualTo(wsSystem.getUserNo());
        assertThat(survived.getFolderId()).isNull();
    }

    @Test
    void proxySelectedVhosts_followTheListOrder() {
        var user = insertUser("folderProxyUser");
        var vhosts = new ProxyVhostService(FACTORY);
        var folders = new ListFolderService(FACTORY, FolderKind.PROXY);
        var first = vhosts.create(user.getUserNo());
        var second = vhosts.create(user.getUserNo());
        vhosts.toggleSelected(first.getVhostId(), user.getUserNo());
        vhosts.toggleSelected(second.getVhostId(), user.getUserNo());
        var f = folders.create(user.getUserNo(), "front");
        var proxyFolder = new ListFolderService.TreeEntry(true, f.getFolderId(), null);
        var inFolder = new ListFolderService.TreeEntry(false, second.getVhostId(), f.getFolderId());
        var atRoot = new ListFolderService.TreeEntry(false, first.getVhostId(), null);

        folders.saveTreeOrder(user.getUserNo(), List.of(proxyFolder, inFolder, atRoot));
        assertThat(vhosts.listSelected(user.getUserNo())).extracting(v -> v.getVhostId())
            .containsExactly(second.getVhostId(), first.getVhostId());

        folders.saveTreeOrder(user.getUserNo(), List.of(atRoot, proxyFolder, inFolder));
        assertThat(vhosts.listSelected(user.getUserNo())).extracting(v -> v.getVhostId())
            .containsExactly(first.getVhostId(), second.getVhostId());
    }
}
