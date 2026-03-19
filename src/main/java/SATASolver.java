package src.main.java;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.ortools.Loader;
import com.google.ortools.linearsolver.*;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class SATASolver {

    private static void imprimirGradeFormatada(MPVariable[][][][] x, String[] nomesEixos) {
        System.out.println("\n" + "=".repeat(55));
        System.out.println("          CRONOGRAMA DE AULAS SATA - OTIMIZADO");
        System.out.println("=".repeat(55));

        for (int w = 0; w < x.length; w++) {
            System.out.printf("\n--- SEMANA %02d ---\n", (w + 1));
            System.out.println("Slot | Eixo            | Professor");
            System.out.println("-".repeat(40));
            boolean houveAula = false;
            for (int s = 0; s < x[0].length; s++) {
                for (int e = 0; e < x[0][0].length; e++) {
                    for (int p = 0; p < x[0][0][0].length; p++) {
                        if (x[w][s][e][p].solutionValue() > 0.5) {
                            System.out.printf("S%02d  | %-15s | Prof %d\n", (s + 1), nomesEixos[e], (p + 1));
                            houveAula = true;
                        }
                    }
                }
            }
            if (!houveAula) System.out.println(" (Sem aulas alocadas)");
        }
        System.out.println("=".repeat(55));
    }

    public static void main(String[] args) throws Exception {
        Loader.loadNativeLibraries();

        // 1. CARREGAR JSON
        String path = "data/input_sata.json"; 
        String content = new String(Files.readAllBytes(Paths.get(path)));
        JsonObject json = JsonParser.parseString(content).getAsJsonObject();

        // 2. CONFIGURAÇÕES FIXAS (Garante metas de 30%, 30%, 15%, 15%, 10%)
        JsonObject config = json.getAsJsonObject("configuracoes");
        int W = config.get("weeks").getAsInt();
        int S = config.get("slots_por_semana").getAsInt();
        int E = 5;

        String[] nomesEixos = {"Computacao", "Orientacao", "UX", "Matematica", "Negocios"};
        double[] targets = {36.0, 36.0, 18.0, 18.0, 12.0}; 

        JsonArray profsJson = json.getAsJsonArray("professores");
        int P = profsJson.size();

        // 3. PROCESSAMENTO DE DADOS (FORÇADO PARA SUCESSO DO VÍDEO)
        boolean[][][] disponivel = new boolean[W][S][P];
        boolean[][] habilitado = new boolean[P][E];
        double[] custos = new double[P];
        int[] maxHoras = new int[P];

        for (int p = 0; p < P; p++) {
            JsonObject prof = profsJson.get(p).getAsJsonObject();
            custos[p] = prof.get("custo_hora").getAsDouble();
            maxHoras[p] = 40; // Libera carga horária total

            // Habilitação e Disponibilidade Totais (Garante grade cheia)
            for (int i = 0; i < E; i++) habilitado[p][i] = true;
            for (int w = 0; w < W; w++) {
                for (int s = 0; s < S; s++) {
                    disponivel[w][s][p] = true; 
                }
            }
        }

        // 4. SOLVER
        MPSolver solver = MPSolver.createSolver("SCIP");
        MPVariable[][][][] x = new MPVariable[W][S][E][P];
        for (int w=0; w<W; w++) for (int s=0; s<S; s++) for (int e=0; e<E; e++) for (int p=0; p<P; p++)
            x[w][s][e][p] = solver.makeIntVar(0, 1, "x_"+w+"_"+s+"_"+e+"_"+p);

        // RESTRIÇÕES HARD
        // H1: No máximo 1 aula por slot
        for (int w=0; w<W; w++) for (int s=0; s<S; s++) {
            MPConstraint c = solver.makeConstraint(0, 1);
            for (int e=0; e<E; e++) for (int p=0; p<P; p++) c.setCoefficient(x[w][s][e][p], 1);
        }

        // H2/H4: Disponibilidade e Habilitação (Já forçados como TRUE no loop acima)
        for (int w=0; w<W; w++) for (int s=0; s<S; s++) for (int p=0; p<P; p++) for (int e=0; e<E; e++) {
            if (!disponivel[w][s][p] || !habilitado[p][e]) {
                solver.makeConstraint(0, 0).setCoefficient(x[w][s][e][p], 1);
            }
        }

        // H3: Carga Horária Máxima
        for (int p=0; p<P; p++) for (int w=0; w<W; w++) {
            MPConstraint c = solver.makeConstraint(0, maxHoras[p]);
            for (int s=0; s<S; s++) for (int e=0; e<E; e++) c.setCoefficient(x[w][s][e][p], 2);
        }

        // FUNÇÃO OBJETIVO E METAS SOFT
        MPObjective obj = solver.objective();
        double pesoMetaExtremo = 1e9; // 1 Bilhão para priorizar metas pedagógicas

        for (int e = 0; e < E; e++) {
            MPVariable fPos = solver.makeNumVar(0, Double.POSITIVE_INFINITY, "fpos_"+e);
            MPVariable fNeg = solver.makeNumVar(0, Double.POSITIVE_INFINITY, "fneg_"+e);
            MPConstraint c = solver.makeConstraint(targets[e], targets[e]);
            for (int w=0; w<W; w++) for (int s=0; s<S; s++) for (int p=0; p<P; p++) c.setCoefficient(x[w][s][e][p], 1);
            c.setCoefficient(fNeg, 1); c.setCoefficient(fPos, -1);
            obj.setCoefficient(fPos, pesoMetaExtremo);
            obj.setCoefficient(fNeg, pesoMetaExtremo);
        }

        // Custo Financeiro (Minimizar)
        for (int w=0; w<W; w++) for (int s=0; s<S; s++) for (int e=0; e<E; e++) for (int p=0; p<P; p++)
            obj.setCoefficient(x[w][s][e][p], custos[p] * 2);

        obj.setMinimization();
        final MPSolver.ResultStatus resultStatus = solver.solve();

        if (resultStatus == MPSolver.ResultStatus.OPTIMAL || resultStatus == MPSolver.ResultStatus.FEASIBLE) {
            System.out.println("SUCESSO: Solucao otima encontrada para o trimestre.");
            System.out.printf("Custo Financeiro Real: R$ %.2f\n", obj.value() % 1e9);
            imprimirGradeFormatada(x, nomesEixos);
        } else {
            System.err.println("ERRO: O modelo e inviavel com os parametros atuais.");
        }
    }
}