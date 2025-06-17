import java.util.Scanner;

public class MainEntry {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        while (running) {
            System.out.println("\n══════════════════════════════════════════════");
            System.out.println("🧪 MENÚ PRINCIPAL - MÓDULO DE TEST DE CARGA 🧪");
            System.out.println("══════════════════════════════════════════════");
            System.out.println("1. Ejecutar Test de Alta Carga de Votación");
            System.out.println("2. Ejecutar Test de Alta Carga de Consultas");
            System.out.println("0. Salir");
            System.out.print("👉 Selecciona una opción: ");

            String option = scanner.nextLine();

            switch (option) {
                case "1":
                    System.out.println("\n🔹 Ejecutando HighLoadTest...");
                    HighLoadTest.main(new String[]{});
                    break;
                case "2":
                    System.out.println("\n🔹 Ejecutando QueryLoadTest...");
                    QueryLoadTest.main(new String[]{});
                    break;
                case "0":
                    System.out.println("👋 Cerrando módulo de pruebas. ¡Hasta luego!");
                    running = false;
                    break;
                default:
                    System.out.println("❌ Opción inválida. Intenta de nuevo.");
            }
        }

        scanner.close();
    }
}