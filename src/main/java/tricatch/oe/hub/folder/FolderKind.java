package tricatch.oe.hub.folder;

/**
 * Which sidebar list a folder belongs to. Both lists share one folder implementation; this only
 * names the tables/columns it works on (ListFolderMapper substitutes them with ${...}, so they
 * must stay code constants - never user input).
 */
public enum FolderKind {

    HOSTS("HOSTS_FOLDER", "HOSTS_PFILE", "hosts_id"),
    PROXY("PROXY_FOLDER", "PROXY_VHOST", "vhost_id");

    private final String folderTable;
    private final String itemTable;
    private final String itemIdColumn;

    FolderKind(String folderTable, String itemTable, String itemIdColumn) {
        this.folderTable = folderTable;
        this.itemTable = itemTable;
        this.itemIdColumn = itemIdColumn;
    }

    public String getFolderTable() { return folderTable; }
    public String getItemTable() { return itemTable; }
    public String getItemIdColumn() { return itemIdColumn; }
}
