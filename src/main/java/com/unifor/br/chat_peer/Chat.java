package com.unifor.br.chat_peer;
import java.io.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class Chat {

    private String userName;
    private ServerSocket serverSocket;
    private List<Socket> connections = new ArrayList<>();
    private Map<Socket, PrintWriter> writers = new HashMap<>();
    private List<String> messageHistory = new ArrayList<>();
    private static final String HISTORY_FILE = "chat_history.txt";
    private volatile boolean running = true;

    public Chat(String userName, int port) {
        this.userName = userName;
        try {
            this.serverSocket = new ServerSocket(port);
            loadHistory();
            System.out.println(userName + " está ouvindo na porta: " + port);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void loadHistory() {
        try {
            if (Files.exists(Paths.get(HISTORY_FILE))) {
                messageHistory = Files.readAllLines(Paths.get(HISTORY_FILE));
                System.out.println("Histórico carregado: " + messageHistory.size() + " mensagens");
            }
        } catch (IOException e) {
            System.err.println("Erro ao carregar histórico: " + e.getMessage());
        }
    }

    private void saveMessage(String message) {
        try {
            Files.write(
                    Paths.get(HISTORY_FILE),
                    (message + System.lineSeparator()).getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            System.err.println("Erro ao salvar mensagem: " + e.getMessage());
        }
    }

    public void start() {
        new Thread(this::listenForConnections).start();
    }

    public void startUserInput() {
        new Thread(this::listenForUserinput).start();
    }

    private void listenForUserinput() {
        try {
            BufferedReader userInput = new BufferedReader(new InputStreamReader(System.in));
            while (running) {
                String mensagem = userInput.readLine();
                if (mensagem == null) break;

                if (mensagem.equalsIgnoreCase("/quit") || mensagem.equalsIgnoreCase("/exit")) {
                    shutdown();
                    break;
                } else if (mensagem.equalsIgnoreCase("/history")){
                    showHistory();
                } else if (mensagem.equalsIgnoreCase("/clear")) {
                    clearHistory();
                } else {
                    broadcastMessage(mensagem);
                }
            }
        } catch (IOException e) {
            if (running) {
                throw new RuntimeException(e);
            }
        }
    }

    private synchronized void broadcastMessage(String mensagem) {
        String formattedMessage = userName + ": " + mensagem;
        messageHistory.add(formattedMessage);
        saveMessage(formattedMessage);

        for (Socket socket : connections) {
            PrintWriter writer = writers.get(socket);
            if (writer != null) {
                writer.println(formattedMessage);
            }
        }
    }

    private synchronized void showHistory() {
        System.out.println("\n=== Histórico de mensagens ===");
        if (messageHistory.isEmpty()) {
            System.out.println("Nenhuma mensagem registrada");
        } else {
            for (String msg : messageHistory) {
                System.out.println(msg);
            }
        }
        System.out.println("=== Fim do Histórico ===\n");
    }

    private synchronized void clearHistory() {
        try {
            Files.deleteIfExists(Paths.get(HISTORY_FILE));
            messageHistory.clear();
            System.out.println("Histórico limpo com sucesso!");
        } catch (IOException e) {
            System.err.println("Erro ao limpar histórico: " + e.getMessage());
        }
    }

    private void listenForConnections() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                synchronized (this) {
                    connections.add(socket);
                    writers.put(socket, writer);
                }
                new Thread(() -> handleConnection(socket)).start();
            } catch (IOException e) {
                if (running) {
                    System.err.println("Erro ao aceitar conexão: " + e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String mensagem;
            while ((mensagem = in.readLine()) != null && running) {
                synchronized (this) {
                    messageHistory.add(mensagem);
                    saveMessage(mensagem);
                }
                System.out.println(mensagem);
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Conexão perdida");
            }
        } finally {
            synchronized (this) {
                connections.remove(socket);
                writers.remove(socket);
            }
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void connectionToPeer(String host, int port) {
        try {
            Socket socket = new Socket(host, port);
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            synchronized (this) {
                connections.add(socket);
                writers.put(socket, writer);
            }
            new Thread(() -> handleConnection(socket)).start();
            System.out.println("Conectado a um peer em: " + host + ":" + port);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void shutdown() {
        running = false;
        System.out.println("\n=== Encerrando chat ===");

        synchronized (this) {
            for (Socket socket : connections) {
                try {
                    PrintWriter writer = writers.get(socket);
                    if (writer != null) {
                        writer.close();
                    }
                    socket.close();
                } catch (IOException e) {
                    System.err.println("Erro ao fechar conexão: " + e.getMessage());
                }
            }
            connections.clear();
            writers.clear();
        }

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Erro ao fechar servidor: " + e.getMessage());
        }

        System.out.println("Chat encerrado com sucesso!");
        System.exit(0);
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("Digite o nome do usuário:");
        String userName = scanner.nextLine();
        System.out.println("Digite a porta para escutar:");
        int port = Integer.parseInt(scanner.nextLine());

        Chat peer = new Chat(userName, port);
        peer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            peer.shutdown();
        }));

        while (true) {
            System.out.println("Deseja conectar a outro peer? (S/N):");
            String resposta = scanner.nextLine();
            if (resposta.equalsIgnoreCase("s")) {
                System.out.println("Digite o endereço do outro host:");
                String peerHost = scanner.nextLine();
                System.out.println("Digite a porta de outro peer:");
                int peerPort = Integer.parseInt(scanner.nextLine());
                peer.connectionToPeer(peerHost, peerPort);
            } else {
                break;
            }
        }

        System.out.println("\n=== Chat iniciado! Digite suas mensagens abaixo: ===");
        System.out.println("Digite /history para ver todas as mensagens");
        System.out.println("Digite /clear para limpar o histórico");
        System.out.println("Digite /quit ou /exit para sair\n");
        peer.startUserInput();
    }
}