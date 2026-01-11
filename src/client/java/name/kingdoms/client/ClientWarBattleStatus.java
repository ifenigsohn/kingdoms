package name.kingdoms.client;

public final class ClientWarBattleStatus {
    private ClientWarBattleStatus() {}

    public static volatile boolean active = false;

    // current
    public static volatile int ticketsFriend = 0;
    public static volatile int ticketsEnemy = 0;
    public static volatile float moraleFriend = 0f;
    public static volatile float moraleEnemy  = 0f;

    // start/max (used for bar percent)
    public static volatile int ticketsStartFriend = 0;
    public static volatile int ticketsStartEnemy = 0;
    public static volatile float moraleStartFriend = 100f;
    public static volatile float moraleStartEnemy  = 100f;

    /** Call this when a battle starts OR when you first receive battle status. */
    public static void begin(int tfStart, int teStart, float mfStart, float meStart) {
        active = true;
        ticketsStartFriend = Math.max(1, tfStart);
        ticketsStartEnemy  = Math.max(1, teStart);
        moraleStartFriend  = Math.max(1f, mfStart);
        moraleStartEnemy   = Math.max(1f, meStart);
    }

    /** Call this for updates during battle. */
    public static void update(int tf, int te, float mf, float me) {
        active = true;
        ticketsFriend = Math.max(0, tf);
        ticketsEnemy  = Math.max(0, te);
        moraleFriend  = Math.max(0f, mf);
        moraleEnemy   = Math.max(0f, me);

        // fallback: if we never got explicit starts, treat first seen as max
        if (ticketsStartFriend <= 0) ticketsStartFriend = Math.max(1, ticketsFriend);
        if (ticketsStartEnemy  <= 0) ticketsStartEnemy  = Math.max(1, ticketsEnemy);
        if (moraleStartFriend  <= 0f) moraleStartFriend  = Math.max(1f, moraleFriend);
        if (moraleStartEnemy   <= 0f) moraleStartEnemy   = Math.max(1f, moraleEnemy);
    }

    public static void clear() {
        active = false;
        ticketsFriend = ticketsEnemy = 0;
        moraleFriend = moraleEnemy = 0f;
        ticketsStartFriend = ticketsStartEnemy = 0;
        moraleStartFriend = moraleStartEnemy = 100f;
    }
}
