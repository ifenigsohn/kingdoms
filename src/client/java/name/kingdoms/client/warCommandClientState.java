package name.kingdoms.client;

/**
 * Client-only cache for the currently selected command group.
 * 0 = FOOTMEN, 1 = ARCHERS, 2 = BOTH
 */
public final class warCommandClientState {
    private warCommandClientState() {}

    private static volatile int selectedGroup = 0;

    public static void setSelectedGroup(int group) {
        selectedGroup = group;
    }

    public static int getSelectedGroup() {
        return selectedGroup;
    }

    public static String getSelectedGroupName() {
        return switch (selectedGroup) {
            case 1 -> "ARCHERS";
            case 2 -> "BOTH";
            default -> "FOOTMEN";
        };
    }
}