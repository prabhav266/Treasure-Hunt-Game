import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Server.java (fixed duplicates)
 *
 * - Race & Quiz modes
 * - Bots spawn when exactly one human connected
 * - Dynamic grid sizing
 * - Leaderboard persisted to leaderboard.json (simple JSON-like)
 *
 * Compile:
 *   javac --release 8 Server.java
 * Run:
 *   java Server
 */
public class Server {
    // ======= Config =======
    private static final int PORT = 12345;
    private static final Random RAND = new Random();
    private static final int BASE_TREASURES = 5;
    private static final int QUIZ_TIME_LIMIT_MS = 15_000;
    private static final String LEADERBOARD_FILE = "leaderboard.json";

    // ======= Runtime state =======
    private static final CopyOnWriteArrayList<ClientHandler> clients = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Bot> bots = new CopyOnWriteArrayList<>();
    private static final List<int[]> treasures = Collections.synchronizedList(new ArrayList<int[]>());

    private static final Map<String, Double> leaderboard = new ConcurrentHashMap<>();

    private static volatile boolean gameStarted = false;
    private static volatile String mode = "race"; // "race" or "quiz"
    private static volatile int gridSize = 10;
    private static volatile int treasureCount = BASE_TREASURES;
    private static volatile long raceStartMillis = 0L;

    private static final QuizManager quizManager = new QuizManager();

    public static void main(String[] args) throws IOException {
        System.out.println("=== Treasure Hunt Server ===");
        loadLeaderboard();

        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("Listening on port " + PORT + " ...");

        // accept clients on background thread
        Thread acceptThread = new Thread(() -> {
            while (true) {
                try {
                    Socket s = serverSocket.accept();
                    ClientHandler ch = new ClientHandler(s);
                    new Thread(ch).start();
                } catch (IOException e) {
                    System.out.println("Accept error: " + e.getMessage());
                    break;
                }
            }
        });
        acceptThread.setDaemon(true);
        acceptThread.start();

        // server console for admin commands
        try (Scanner sc = new Scanner(System.in)) {
            while (true) {
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) {
                    System.out.println("Shutting down server...");
                    System.exit(0);
                } else if (line.equalsIgnoreCase("clients")) {
                    System.out.println("Humans: " + clients.size() + ", bots: " + bots.size());
                } else if (line.equalsIgnoreCase("map")) {
                    printServerMap();
                } else if (line.equalsIgnoreCase("leaderboard")) {
                    System.out.println(renderLeaderboard());
                }
            }
        }
    }

    // ======= Leaderboard persistence =======
    private static synchronized void loadLeaderboard() {
        File f = new File(LEADERBOARD_FILE);
        if (!f.exists()) {
            System.out.println("No leaderboard file found; starting empty leaderboard.");
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln.trim());
            String txt = sb.toString().trim();
            if (txt.startsWith("{") && txt.endsWith("}")) {
                txt = txt.substring(1, txt.length() - 1).trim();
                if (!txt.isEmpty()) {
                    String[] pairs = txt.split(",");
                    for (String p : pairs) {
                        String[] kv = p.split(":");
                        if (kv.length != 2) continue;
                        String k = kv[0].trim();
                        String v = kv[1].trim();
                        if (k.startsWith("\"") && k.endsWith("\"")) k = k.substring(1, k.length() - 1);
                        try { leaderboard.put(k, Double.parseDouble(v)); } catch (NumberFormatException ignore) {}
                    }
                }
            }
            System.out.println("Loaded leaderboard (" + leaderboard.size() + " records).");
        } catch (IOException e) {
            System.out.println("Failed to load leaderboard: " + e.getMessage());
        }
    }

    private static synchronized void saveLeaderboard() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(LEADERBOARD_FILE))) {
            pw.print("{");
            boolean first = true;
            for (Map.Entry<String, Double> e : leaderboard.entrySet()) {
                if (!first) pw.print(",");
                pw.print("\"" + e.getKey().replace("\"", "") + "\":" + String.format(Locale.US, "%.3f", e.getValue()));
                first = false;
            }
            pw.println("}");
        } catch (IOException e) {
            System.out.println("Failed to save leaderboard: " + e.getMessage());
        }
    }

    private static String renderLeaderboard() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== LEADERBOARD (Race fastest times) ===\n");
        List<Map.Entry<String, Double>> list = new ArrayList<>(leaderboard.entrySet());
        list.sort(Comparator.comparingDouble(Map.Entry::getValue));
        for (Map.Entry<String, Double> e : list) {
            sb.append(e.getKey()).append(" - ").append(String.format(Locale.US, "%.2f", e.getValue())).append(" sec\n");
        }
        return sb.toString();
    }

    // ======= Map & treasures =======
    private static synchronized void recalcGridAndTreasures() {
        int total = clients.size() + bots.size();
        if (total <= 4) gridSize = 10;
        else if (total <= 10) gridSize = 15;
        else gridSize = 20;
        treasureCount = Math.max(BASE_TREASURES, Math.max(1, total));
    }

    private static synchronized void initTreasures() {
        treasures.clear();
        for (int i = 0; i < treasureCount; i++) {
            treasures.add(new int[]{RAND.nextInt(gridSize), RAND.nextInt(gridSize)});
        }
        System.out.println("Placed " + treasures.size() + " treasures on " + gridSize + "x" + gridSize);
    }

    private static boolean inBounds(int x, int y) { return x >= 0 && y >= 0 && x < gridSize && y < gridSize; }

    private static char[][] buildGrid(boolean showTreasures) {
        char[][] g = new char[gridSize][gridSize];
        for (int r = 0; r < gridSize; r++) Arrays.fill(g[r], '.');

        if (showTreasures) {
            synchronized (treasures) {
                for (int[] t : treasures) if (inBounds(t[0], t[1])) g[t[1]][t[0]] = 'T';
            }
        }

        for (ClientHandler c : clients) if (inBounds(c.x, c.y)) g[c.y][c.x] = c.name.charAt(0);
        for (Bot b : bots) if (inBounds(b.x, b.y)) {
            char cur = g[b.y][b.x];
            if (cur == '.' || cur == 'T') g[b.y][b.x] = b.name.charAt(0);
            else g[b.y][b.x] = '*';
        }
        return g;
    }

    private static String renderClientMap() {
    StringBuilder sb = new StringBuilder();
    char[][] g = buildGrid(true);   

        sb.append("\n--- MAP ").append(gridSize).append("x").append(gridSize).append(" ---\n");
        for (int r = 0; r < gridSize; r++) {
            for (int c = 0; c < gridSize; c++) sb.append(g[r][c]).append(' ');
            sb.append('\n');
        }
        sb.append("Treasures left: ").append(treasures.size()).append('\n');
        return sb.toString();
    }

    private static void printServerMap() {
        char[][] g = buildGrid(true);
        StringBuilder sb = new StringBuilder();
        sb.append("\n--- SERVER MAP ").append(gridSize).append("x").append(gridSize).append(" ---\n");
        for (int r = 0; r < gridSize; r++) {
            for (int c = 0; c < gridSize; c++) sb.append(g[r][c]).append(' ');
            sb.append('\n');
        }
        sb.append("Treasures left: ").append(treasures.size()).append('\n');
        System.out.println(sb.toString());
    }

    private static void broadcastToClients(String msg) {
        for (ClientHandler c : clients) c.send(msg);
    }

    // ======= Quiz manager =======
    private static class QuizManager {
        private final List<Object> turnList = new ArrayList<>(); // mix of ClientHandler and Bot
        private int idx = 0;
        private Timer turnTimer = null;
        private volatile Integer currentAnswer = null;
        private volatile Object activeParticipant = null;
        private volatile int qA = 0, qB = 0;
        private volatile char qOp = '+';

        synchronized void buildTurnList() {
            turnList.clear();
            turnList.addAll(clients);
            turnList.addAll(bots);
            if (idx >= turnList.size()) idx = 0;
        }

        synchronized void start() {
            buildTurnList();
            if (turnList.isEmpty()) return;
            broadcastToClients("[QUIZ] Starting quiz (turn-based).");
            scheduleNextTurn(500);
        }

        synchronized void scheduleNextTurn(long delayMs) {
            new Timer(true).schedule(new TimerTask() {
                @Override public void run() { nextTurn(); }
            }, delayMs);
        }

        synchronized void nextTurn() {
            if (turnTimer != null) { turnTimer.cancel(); turnTimer = null; }
            buildTurnList();
            if (turnList.isEmpty()) return;
            if (idx >= turnList.size()) idx = 0;
            activeParticipant = turnList.get(idx);
            idx = (idx + 1) % turnList.size();
            String who = (activeParticipant instanceof ClientHandler) ? ((ClientHandler) activeParticipant).name : ((Bot) activeParticipant).name + " (BOT)";
            broadcastToClients("[QUIZ] It is now " + who + "'s turn.");
            generateQuestion();

            if (activeParticipant instanceof ClientHandler) {
                ClientHandler ch = (ClientHandler) activeParticipant;
                ch.send("[QUESTION] " + currentQuestion());
                ch.send("[QUESTION_PROMPT] Reply: ANSWER <number>");
                for (ClientHandler other : clients) if (other != ch) other.send("[QUIZ] Waiting for " + ch.name + "'s answer.");
            } else {
                Bot b = (Bot) activeParticipant;
                broadcastToClients("[QUIZ] " + b.name + " (BOT) is answering...");
            }

            // start per-turn timer
            turnTimer = new Timer();
            turnTimer.schedule(new TimerTask() {
                @Override public void run() {
                    synchronized (QuizManager.this) {
                        String whoTimed = (activeParticipant instanceof ClientHandler) ? ((ClientHandler) activeParticipant).name : ((Bot) activeParticipant).name;
                        broadcastToClients("[QUIZ] " + whoTimed + " timed out.");
                        if (activeParticipant instanceof Bot) ((Bot) activeParticipant).onAnswerResult(false);
                        scheduleNextTurn(500);
                    }
                }
            }, QUIZ_TIME_LIMIT_MS);

            // if bot's turn, schedule its attempt
            if (activeParticipant instanceof Bot) {
                Bot b = (Bot) activeParticipant;
                int delay = 500 + RAND.nextInt(1500);
                new Timer(true).schedule(new TimerTask() {
                    @Override public void run() { b.attemptAnswer(currentAnswer); }
                }, delay);
            }
        }

        private void generateQuestion() {
            qA = RAND.nextInt(10) + 1;
            qB = RAND.nextInt(10) + 1;
            int op = RAND.nextInt(4);
            switch (op) {
                case 0: qOp = '+'; currentAnswer = qA + qB; break;
                case 1: qOp = '-'; currentAnswer = qA - qB; break;
                case 2: qOp = '*'; currentAnswer = qA * qB; break;
                default:
                    // produce integer division with dividend divisible by divisor
                    qB = RAND.nextInt(4) + 1;
                    int multiplier = RAND.nextInt(5) + 1;
                    qA = qB * multiplier;
                    qOp = '/';
                    currentAnswer = qA / qB;
                    break;
            }
        }

        private String currentQuestion() {
            return qA + " " + qOp + " " + qB + " = ?";
        }

        synchronized void receiveClientAnswer(ClientHandler ch, String text) {
            if (ch != activeParticipant) { ch.send("[QUIZ] Not your turn."); return; }
            if (currentAnswer == null) { ch.send("[QUIZ] No active question."); return; }
            int v;
            try { v = Integer.parseInt(text.trim()); } catch (NumberFormatException e) { ch.send("[QUIZ] Send a numeric answer."); return; }
            if (turnTimer != null) { turnTimer.cancel(); turnTimer = null; }
            if (v == currentAnswer) {
                broadcastToClients("[QUIZ] " + ch.name + " answered correctly.");
                ch.canMove = true;
                ch.send("[QUIZ] You may move now (W/A/S/D). Movement has no time limit.");
                // when client moves, they must call notifyMoveConsumed()
            } else {
                ch.send("[QUIZ] Wrong answer.");
                scheduleNextTurn(500);
            }
        }

        synchronized void notifyMoveConsumed() {
            scheduleNextTurn(500);
        }
    } // end QuizManager

    // ======= ClientHandler (human) =======
    private static class ClientHandler implements Runnable {
        final Socket socket;
        BufferedReader in;
        PrintWriter out;
        String name = "Player";
        int x = 0, y = 0;
        boolean canMove = false;
        long startMillis = 0L;

        ClientHandler(Socket s) { this.socket = s; }

        void send(String msg) { try { if (out != null) out.println(msg); } catch (Exception ignored) {} }

        @Override public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                send("Enter your name:");
                String nm = in.readLine();
                name = (nm != null && !nm.trim().isEmpty()) ? nm.trim() : ("Player" + RAND.nextInt(1000));

                clients.add(this);
                System.out.println("Human joined: " + name + " (humans: " + clients.size() + ")");

                synchronized (Server.class) {
                    recalcGridAndTreasures();
                    if (!gameStarted && clients.size() == 1) {
                        send("You are the host. Choose mode: 'race' or 'quiz' (type exactly):");
                        String choice = in.readLine();
                        mode = ("quiz".equalsIgnoreCase(choice)) ? "quiz" : "race";
                        gameStarted = true;
                        recalcGridAndTreasures();
                        initTreasures();
                        raceStartMillis = System.currentTimeMillis();
                        if ("quiz".equals(mode)) quizManager.start();
                    } else if (!gameStarted) {
                        mode = "race";
                        gameStarted = true;
                        recalcGridAndTreasures();
                        initTreasures();
                        raceStartMillis = System.currentTimeMillis();
                    }

                    if ("quiz".equals(mode) && clients.size() == 1 && bots.isEmpty()) {
    for (int i = 1; i <= 2; i++) {
        Bot b = new Bot("Bot" + i, 0.35);
        bots.add(b);
        new Thread(b).start();
    }
}

                }

                x = RAND.nextInt(gridSize);
                y = RAND.nextInt(gridSize);
                startMillis = System.currentTimeMillis();
                send("[SERVER] Welcome " + name + "! You spawned at (" + x + "," + y + "). Mode: " + mode.toUpperCase());
                send(renderClientMap());
                broadcastToClients("[SERVER] " + name + " joined.");

                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if ("exit".equalsIgnoreCase(line)) break;
                    if ("map".equalsIgnoreCase(line)) { send(renderClientMap()); continue; }
                    if ("leaderboard".equalsIgnoreCase(line)) { send(renderLeaderboard()); continue; }

                    if ("race".equalsIgnoreCase(mode)) {
                        handleRaceCommand(line);
                    } else { // quiz mode
    if (line.toLowerCase().startsWith("answer ")) {
        String[] parts = line.split(" ", 2);
        if (parts.length < 2) { send("[QUIZ] Invalid ANSWER format."); continue; }
        quizManager.receiveClientAnswer(this, parts[1]);
        continue;
    }

    // ✅ Handle movement after correct answer
    if (line.length() == 1 && "wasdWASD".contains(line)) {
        char d = Character.toLowerCase(line.charAt(0));
        if (!canMove) {
            send("[QUIZ] You cannot move now. Answer on your turn first.");
            continue;
        }

        performMove(d);
        checkTreasureForClient(this);           // ✅ Detect treasure after move
        broadcastToClients(renderClientMap());  // ✅ Update map for all players
        canMove = false;

        quizManager.notifyMoveConsumed();       // ✅ Move to next player's turn
        continue;
    }

    send("[SERVER] Unknown command in quiz mode. Use ANSWER <number>, W/A/S/D (if allowed), MAP, LEADERBOARD, EXIT.");
}
                }
            } catch (IOException e) {
                System.out.println("Connection lost for " + name + ": " + e.getMessage());
            } finally { cleanup(); }
        }

        private void handleRaceCommand(String line) {
            if (line.length() == 1 && "wasdWASD".contains(line)) {
                char d = Character.toLowerCase(line.charAt(0));
                performMove(d);
                checkTreasureForClient(this);
                broadcastToClients(renderClientMap());
            } else {
                send("[SERVER] Unknown command in race mode. Use W/A/S/D, MAP, LEADERBOARD, EXIT.");
            }
        }

        private void performMove(char d) {
            switch (d) {
                case 'w': y = Math.max(0, y - 1); break;
                case 's': y = Math.min(gridSize - 1, y + 1); break;
                case 'a': x = Math.max(0, x - 1); break;
                case 'd': x = Math.min(gridSize - 1, x + 1); break;
            }
        }

        private void checkTreasureForClient(ClientHandler c) {
            synchronized (treasures) {
                Iterator<int[]> it = treasures.iterator();
                while (it.hasNext()) {
                    int[] t = it.next();
                    if (t[0] == c.x && t[1] == c.y) {
                        it.remove();
                        broadcastToClients("[SERVER] " + c.name + " found a treasure!");
                        printServerMap();
                        if ("race".equals(mode) && treasures.isEmpty()) {
    double timeSec = (System.currentTimeMillis() - c.startMillis) / 1000.0;
    synchronized (leaderboard) {
        Double prev = leaderboard.get(c.name);
        if (prev == null || timeSec < prev) { leaderboard.put(c.name, timeSec); saveLeaderboard(); }
    }
    broadcastToClients("[RESULT] " + c.name + " finished the race in " + String.format(Locale.US, "%.2f", timeSec) + " sec");
    broadcastToClients("[FINISH]:" + c.name + ":" + String.format(Locale.US, "%.3f", timeSec));
    broadcastToClients("[SERVER] All treasures found! Race over 🏁");
    gameStarted = false; // stop the race
    return; // DO NOT respawn new treasures
}
 else if (treasures.isEmpty()) {
                            recalcGridAndTreasures();
                            initTreasures();
                            printServerMap();
                            broadcastToClients(renderClientMap());
                        }
                        break;
                    }
                }
            }
        }

        private void cleanup() {
            try { socket.close(); } catch (IOException ignored) {}
            clients.remove(this);
            broadcastToClients("[SERVER] " + name + " left.");
            synchronized (Server.class) {
                if (clients.size() == 1 && bots.isEmpty()) {
                    for (int i = 1; i <= 2; i++) { Bot b = new Bot("Bot" + i, 0.35); bots.add(b); new Thread(b).start(); }
                } else if (clients.size() > 1 && !bots.isEmpty()) {
                    for (Bot b : bots) b.active = false;
                    bots.clear();
                }
                recalcGridAndTreasures();
                initTreasures();
            }
        }
    } // end ClientHandler

    // ======= Bot class =======
    private static class Bot implements Runnable {
        final String name;
        final double correctProb;
        volatile int x, y;
        volatile boolean canMove = false;
        volatile boolean active = true;

        Bot(String name, double correctProb) {
            this.name = name;
            this.correctProb = correctProb;
            this.x = RAND.nextInt(Math.max(1, gridSize));
            this.y = RAND.nextInt(Math.max(1, gridSize));
        }

        void attemptAnswer(Integer correctAnswer) {
            if (!active) return;
            boolean correct = RAND.nextDouble() < correctProb;
            if (correct) {
                broadcastToClients("[QUIZ] " + name + " (BOT) answered correctly.");
                canMove = true;
                new Timer(true).schedule(new TimerTask() {
                    @Override public void run() { performBotMoveAfterQuiz(); }
                }, 700 + RAND.nextInt(800));
            } else {
                broadcastToClients("[QUIZ] " + name + " (BOT) answered incorrectly.");
                quizManager.scheduleNextTurn(400);
            }
        }

        void performBotMoveAfterQuiz() {
            if (!canMove) { quizManager.notifyMoveConsumed(); return; }
            char[] dirs = new char[]{'w','a','s','d'};
            char d = dirs[RAND.nextInt(dirs.length)];
            switch (d) {
                case 'w': y = Math.max(0, y - 1); break;
                case 's': y = Math.min(gridSize - 1, y + 1); break;
                case 'a': x = Math.max(0, x - 1); break;
                case 'd': x = Math.min(gridSize - 1, x + 1); break;
            }
            canMove = false;
            broadcastToClients("[BOT MOVE] " + name + " moved " + Character.toUpperCase(d) + " to (" + x + "," + y + ")");
            checkTreasureForBot(this);
            broadcastToClients(renderClientMap());
            quizManager.notifyMoveConsumed();
        }

        void onAnswerResult(boolean correct) {
            if (correct) broadcastToClients("[BOT] " + name + " (BOT) got it right.");
            else broadcastToClients("[BOT] " + name + " (BOT) got it wrong.");
        }

        @Override public void run() {
            while (active) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }
    } // end Bot

    private static void checkTreasureForBot(Bot b) {
        synchronized (treasures) {
            Iterator<int[]> it = treasures.iterator();
            while (it.hasNext()) {
                int[] t = it.next();
                if (t[0] == b.x && t[1] == b.y) {
                    it.remove();
                    String msg = b.name + " (BOT) found a treasure!";
                    broadcastToClients("[SERVER] " + msg);
                    printServerMap();
                    if ("race".equals(mode) && treasures.isEmpty()) {
                        double tsec = (System.currentTimeMillis() - raceStartMillis) / 1000.0;
                        synchronized (leaderboard) {
                            Double prev = leaderboard.get(b.name);
                            if (prev == null || tsec < prev) { leaderboard.put(b.name, tsec); saveLeaderboard(); }
                        }
                        broadcastToClients("[RESULT] " + b.name + " finished the race in " + String.format(Locale.US, "%.2f", tsec) + " sec");
                        broadcastToClients("[FINISH]:" + b.name + ":" + String.format(Locale.US, "%.3f", tsec));
                        recalcGridAndTreasures();
                        initTreasures();
                        printServerMap();
                        broadcastToClients(renderClientMap());
                    } else if (treasures.isEmpty()) {
                        recalcGridAndTreasures();
                        initTreasures();
                        printServerMap();
                        broadcastToClients(renderClientMap());
                    }
                    break;
                }
            }
        }
    }

    // ======= small helper =======
    private static void scheduleRunnable(Runnable r, long delayMs) {
        new Timer(true).schedule(new TimerTask() { @Override public void run() { r.run(); } }, delayMs);
    }
}
