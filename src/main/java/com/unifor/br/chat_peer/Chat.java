package com.unifor.br.chat_peer;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Chat {

    private static final String MULTICAST_GROUP   = "230.0.0.1";
    private static final int    MULTICAST_PORT    = 9999;
    private static final int    ANNOUNCE_INTERVAL = 3000;
    private static final String DISCOVERY_PREFIX  = "CHAT_PEER_ANNOUNCE";
    private static final String HELLO_PREFIX      = "HELLO:";
    private static final String HISTORY_FILE      = "chat_history.txt";

    private final String                    userName;
    private final int                       tcpPort;
    private final ServerSocket              serverSocket;
    private final List<Socket>              connections = new ArrayList<>();
    private final Map<Socket, PrintWriter>  writers     = new HashMap<>();
    private final List<String>              messageHistory = new ArrayList<>();

    /**
     * Peers já conectados como "host:portaTCP" — chave canônica.
     * Usada para evitar conexão dupla E como registro de peers conhecidos.
     */
    private final Set<String> connectedPeers = ConcurrentHashMap.newKeySet();

    private volatile boolean running = true;
    private MulticastSocket  listenerSocket;
    private DatagramSocket   announcerSocket;

    // =========================================================================
    // Construtor
    // =========================================================================

    public Chat(String userName, int port) {
        this.userName = userName;
        this.tcpPort  = port;
        try {
            this.serverSocket = new ServerSocket(port);
            loadHistory();
            System.out.println("[INFO] " + userName + " escutando na porta TCP: " + port);
        } catch (IOException e) {
            throw new RuntimeException("Falha ao iniciar ServerSocket", e);
        }
    }

    // =========================================================================
    // Histórico
    // =========================================================================

    private void loadHistory() {
        try {
            if (Files.exists(Paths.get(HISTORY_FILE))) {
                messageHistory.addAll(Files.readAllLines(Paths.get(HISTORY_FILE)));
                System.out.println("[INFO] Histórico carregado: " + messageHistory.size() + " mensagens");
            }
        } catch (IOException e) {
            System.err.println("[ERRO] Ao carregar histórico: " + e.getMessage());
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
            System.err.println("[ERRO] Ao salvar mensagem: " + e.getMessage());
        }
    }

    // =========================================================================
    // Inicialização
    // =========================================================================

    public void start() {
        new Thread(this::listenForConnections, "tcp-acceptor").start();
    }

    public void startUserInput() {
        new Thread(this::listenForUserInput, "user-input").start();
    }

    public void startPeerDiscovery() {
        new Thread(this::announcePresence, "discovery-announcer").start();
        new Thread(this::listenForPeers,   "discovery-listener").start();
        System.out.println("[DISCOVERY] Buscando peers na rede local via Multicast (" + MULTICAST_GROUP + ")...");
    }

    // =========================================================================
    // Descoberta — Anunciador
    // =========================================================================

    private void announcePresence() {
        try {
            announcerSocket = new DatagramSocket();
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            String payload = DISCOVERY_PREFIX + ":" + tcpPort;
            byte[] data    = payload.getBytes();

            while (running) {
                announcerSocket.send(new DatagramPacket(data, data.length, group, MULTICAST_PORT));
                Thread.sleep(ANNOUNCE_INTERVAL);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (running) System.err.println("[ERRO] Anunciador UDP: " + e.getMessage());
        } finally {
            if (announcerSocket != null && !announcerSocket.isClosed()) announcerSocket.close();
        }
    }

    // =========================================================================
    // Descoberta — Receptor
    // =========================================================================

    private void listenForPeers() {
        try {
            listenerSocket = new MulticastSocket(MULTICAST_PORT);
            listenerSocket.joinGroup(InetAddress.getByName(MULTICAST_GROUP));

            byte[] buffer = new byte[256];

            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                listenerSocket.receive(packet);

                String message  = new String(packet.getData(), 0, packet.getLength()).trim();
                String peerHost = packet.getAddress().getHostAddress();

                if (!message.startsWith(DISCOVERY_PREFIX)) continue;

                String[] parts = message.split(":");
                if (parts.length < 2) continue;

                int peerTcpPort;
                try {
                    peerTcpPort = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    continue;
                }

                if (isSelf(peerHost, peerTcpPort)) continue;

                String peerKey = peerHost + ":" + peerTcpPort;

                // Só conecta se ainda não há conexão com este peer
                if (connectedPeers.add(peerKey)) {
                    System.out.println("[DISCOVERY] Novo peer descoberto: " + peerKey + ". Conectando...");
                    new Thread(() -> connectToPeerSafely(peerHost, peerTcpPort),
                            "tcp-connect-" + peerKey).start();
                }
            }
        } catch (IOException e) {
            if (running) System.err.println("[ERRO] Listener UDP: " + e.getMessage());
        } finally {
            if (listenerSocket != null && !listenerSocket.isClosed()) listenerSocket.close();
        }
    }

    private boolean isSelf(String peerHost, int peerPort) {
        if (peerPort != this.tcpPort) return false;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
                while (addresses.hasMoreElements()) {
                    if (addresses.nextElement().getHostAddress().equals(peerHost)) return true;
                }
            }
        } catch (SocketException e) {
            System.err.println("[AVISO] Não foi possível verificar IPs locais: " + e.getMessage());
        }
        return false;
    }

    // =========================================================================
    // Conexão TCP de saída (este peer inicia a conexão)
    // =========================================================================

    private void connectToPeerSafely(String host, int port) {
        try {
            connectionToPeer(host, port);
        } catch (RuntimeException e) {
            String peerKey = host + ":" + port;
            System.err.println("[DISCOVERY] Falha ao conectar a " + peerKey + ": " + e.getMessage());
            connectedPeers.remove(peerKey);
        }
    }

    public void connectionToPeer(String host, int port) {
        try {
            Socket socket = new Socket(host, port);
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);

            // ✅ Handshake: informa ao servidor nossa porta TCP de escuta
            writer.println(HELLO_PREFIX + tcpPort);

            synchronized (this) {
                connections.add(socket);
                writers.put(socket, writer);
            }
            new Thread(() -> handleConnection(socket, host + ":" + port),
                    "tcp-handler-" + host + ":" + port).start();
            System.out.println("[INFO] Conectado ao peer: " + host + ":" + port);
        } catch (IOException e) {
            throw new RuntimeException("Falha ao conectar ao peer " + host + ":" + port, e);
        }
    }

    // =========================================================================
    // TCP — Aceitar conexões de entrada
    // =========================================================================

    private void listenForConnections() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                // Processa handshake em thread separada para não bloquear o acceptor
                new Thread(() -> handleIncomingHandshake(socket), "tcp-handshake").start();
            } catch (IOException e) {
                if (running) System.err.println("[ERRO] Ao aceitar conexão: " + e.getMessage());
            }
        }
    }

    /**
     * Lê a primeira linha do socket recém-aceito.
     * Se for "HELLO:<porta>", verifica se já existe conexão com esse peer.
     * Em caso positivo, fecha o socket duplicado imediatamente.
     */
    private void handleIncomingHandshake(Socket socket) {
        try {
            BufferedReader in     = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter    writer = new PrintWriter(socket.getOutputStream(), true);

            String firstLine = in.readLine();
            if (firstLine == null || !firstLine.startsWith(HELLO_PREFIX)) {
                System.err.println("[AVISO] Handshake inválido. Fechando conexão.");
                socket.close();
                return;
            }

            int remoteTcpPort;
            try {
                remoteTcpPort = Integer.parseInt(firstLine.substring(HELLO_PREFIX.length()).trim());
            } catch (NumberFormatException e) {
                System.err.println("[AVISO] Porta inválida no handshake. Fechando conexão.");
                socket.close();
                return;
            }

            String remoteHost = socket.getInetAddress().getHostAddress();
            String peerKey    = remoteHost + ":" + remoteTcpPort;

            // ✅ Verifica se já existe conexão com este peer (evita duplicata)
            if (!connectedPeers.add(peerKey)) {
                System.out.println("[INFO] Conexão duplicada com " + peerKey + " recusada.");
                socket.close();
                return;
            }

            synchronized (this) {
                connections.add(socket);
                writers.put(socket, writer);
            }
            System.out.println("[INFO] Peer conectado: " + peerKey);

            // Continua para o loop normal de leitura de mensagens
            handleConnection(socket, peerKey);

        } catch (IOException e) {
            if (running) System.err.println("[ERRO] Handshake falhou: " + e.getMessage());
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // =========================================================================
    // TCP — Loop de leitura de mensagens
    // =========================================================================

    private void handleConnection(Socket socket, String peerKey) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String mensagem;
            while ((mensagem = in.readLine()) != null && running) {
                // Ignora linhas de handshake que possam chegar fora de ordem
                if (mensagem.startsWith(HELLO_PREFIX)) continue;
                synchronized (this) {
                    messageHistory.add(mensagem);
                    saveMessage(mensagem);
                }
                System.out.println(mensagem);
            }
        } catch (IOException e) {
            if (running) System.out.println("[INFO] Conexão com " + peerKey + " encerrada.");
        } finally {
            connectedPeers.remove(peerKey);
            synchronized (this) {
                connections.remove(socket);
                writers.remove(socket);
            }
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // =========================================================================
    // Entrada do usuário
    // =========================================================================

    private void listenForUserInput() {
        try (BufferedReader userInput = new BufferedReader(new InputStreamReader(System.in))) {
            while (running) {
                String mensagem = userInput.readLine();
                if (mensagem == null) break;
                switch (mensagem.toLowerCase()) {
                    case "/quit": case "/exit": shutdown(); return;
                    case "/history": showHistory();    break;
                    case "/clear":   clearHistory();   break;
                    case "/peers":   showKnownPeers(); break;
                    default:         broadcastMessage(mensagem);
                }
            }
        } catch (IOException e) {
            if (running) throw new RuntimeException(e);
        }
    }

    // =========================================================================
    // Broadcast
    // =========================================================================

    private synchronized void broadcastMessage(String mensagem) {
        String formatted = userName + ": " + mensagem;
        messageHistory.add(formatted);
        saveMessage(formatted);

        Iterator<Socket> it = connections.iterator();
        while (it.hasNext()) {
            Socket socket = it.next();
            PrintWriter writer = writers.get(socket);
            if (writer != null) {
                writer.println(formatted);
                if (writer.checkError()) {
                    writers.remove(socket);
                    it.remove();
                    try { socket.close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    // =========================================================================
    // Comandos auxiliares
    // =========================================================================

    private synchronized void showHistory() {
        System.out.println("\n=== Histórico de Mensagens ===");
        messageHistory.forEach(System.out::println);
        System.out.println("=== Fim do Histórico ===\n");
    }

    private synchronized void clearHistory() {
        try {
            Files.deleteIfExists(Paths.get(HISTORY_FILE));
            messageHistory.clear();
            System.out.println("[INFO] Histórico limpo!");
        } catch (IOException e) {
            System.err.println("[ERRO] Ao limpar histórico: " + e.getMessage());
        }
    }

    private void showKnownPeers() {
        System.out.println("\n=== Peers Conectados ===");
        if (connectedPeers.isEmpty()) System.out.println("  Nenhum peer conectado.");
        else connectedPeers.forEach(p -> System.out.println("  - " + p));
        System.out.println("=== Fim ===\n");
    }

    // =========================================================================
    // Encerramento
    // =========================================================================

    public void shutdown() {
        running = false;
        System.out.println("\n=== Encerrando chat ===");
        if (announcerSocket != null && !announcerSocket.isClosed()) announcerSocket.close();
        if (listenerSocket  != null && !listenerSocket.isClosed())  listenerSocket.close();
        synchronized (this) {
            connections.forEach(s -> {
                try { writers.get(s).close(); s.close(); } catch (Exception ignored) {}
            });
            connections.clear();
            writers.clear();
        }
        try { if (!serverSocket.isClosed()) serverSocket.close(); } catch (IOException ignored) {}
        System.out.println("[INFO] Chat encerrado!");
        System.exit(0);
    }

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("Digite o nome do usuário: ");
        String userName = scanner.nextLine().trim();
        System.out.print("Digite a porta TCP para escutar: ");
        int port = Integer.parseInt(scanner.nextLine().trim());

        Chat peer = new Chat(userName, port);
        Runtime.getRuntime().addShutdownHook(new Thread(peer::shutdown, "shutdown-hook"));

        peer.start();
        peer.startPeerDiscovery();

        System.out.println("\n=== Chat iniciado! Comandos: /history | /clear | /peers | /quit ===\n");
        peer.startUserInput();
    }
}