import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Client.java — network-ready version
 * Works with the fixed Server.java
 *
 * Features:
 *  - Connects to server on LAN or localhost
 *  - Supports both Race and Quiz modes
 *  - Safe for Windows console (no Unicode)
 *  - Asks for server IP on startup
 */
public class Client {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);

        try {
            // ✅ Ask for server IP
            System.out.print("Enter Server IP (press Enter for localhost): ");
            String ip = sc.nextLine().trim();
            if (ip.isEmpty()) ip = "localhost"; // default

            // ✅ Connect to the server
            Socket socket = new Socket(ip, 12345);
            System.out.println("Connected to server at " + ip + ":12345");

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

            // ✅ Background listener thread for server messages
            Thread listener = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.startsWith("[FINISH]:")) {
                            // Race mode finish message
                            String[] parts = line.split(":");
                            if (parts.length >= 3) {
                                System.out.println("\n>>> " + parts[1] + " finished the race in " + parts[2] + " seconds!");
                            }
                        } else if (line.startsWith("[QUESTION_PROMPT]")) {
                            // Quiz mode question prompt
                            System.out.println("Type your answer using: ANSWER <number>");
                        } else {
                            // Normal server messages
                            System.out.println("> " + line);
                        }
                    }
                } catch (IOException e) {
                    System.out.println("Disconnected from server.");
                }
            });

            listener.setDaemon(true);
            listener.start();

            // ✅ Send player input to server
            while (true) {
                String msg = sc.nextLine();
                out.println(msg);
                if (msg.equalsIgnoreCase("exit")) {
                    System.out.println("Exiting game...");
                    break;
                }
            }

            socket.close();
            sc.close();

        } catch (IOException e) {
            System.out.println("Connection error: " + e.getMessage());
        }
    }
}

