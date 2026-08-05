package com.Emil.TCAutoResearch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.common.lib.research.ResearchManager;
import thaumcraft.common.lib.research.ResearchNoteData;

public final class WeightedResearchSolver {

    private static final int RESULT_CACHE_LIMIT = 8;
    private static final int GEOMETRY_CACHE_LIMIT = 32;
    private static final int STEINER_MAX_ANCHORS = 8;
    private static final int NORMAL_RETRY_BEAM = 16;
    private static final int REPAIR_PLAN_TIMEOUT_MS = 5000;
    private static final int PHYSICAL_TREE_BEAM = 96;
    private static final int PHYSICAL_PATH_ALTERNATIVES = 4;
    private static final Map<String, Result> RESULT_CACHE = new LinkedHashMap<String, Result>(16, 0.75F, true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) {
            return size() > RESULT_CACHE_LIMIT;
        }
    };
    private static final Map<String, BoardGeometry> GEOMETRY_CACHE = new LinkedHashMap<String, BoardGeometry>(
        48,
        0.75F,
        true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BoardGeometry> eldest) {
            return size() > GEOMETRY_CACHE_LIMIT;
        }
    };
    private static CompatibilityGraph compatibilityGraph;

    private WeightedResearchSolver() {}

    public static int resultCacheSize() {
        synchronized (RESULT_CACHE) {
            return RESULT_CACHE.size();
        }
    }

    public static Result solve(ResearchNoteData note, AspectList inventory, Config.SolverSettings settings,
        BooleanSupplier cancelled) {
        return solve(note, inventory, settings, cancelled, true);
    }

    private static Result solve(ResearchNoteData note, AspectList inventory, Config.SolverSettings settings,
        BooleanSupplier cancelled, boolean logFailure) {
        long started = System.nanoTime();
        Metrics metrics = new Metrics();
        Problem problem = Problem.from(note, inventory, settings, cancelled, metrics);
        String cacheKey = problem.cacheKey();
        synchronized (RESULT_CACHE) {
            Result cached = RESULT_CACHE.get(cacheKey);
            if (cached != null && !cancelled.getAsBoolean()) {
                return cached.withDiagnostics(elapsedMillis(started), metrics, true);
            }
        }
        Result result;
        ForcedCorridorFailure corridorFailure = forcedCorridorFailure(problem, true);
        if (corridorFailure != null) {
            result = Result.failure("incompatible_corridor", false, corridorFailure.repairCell.toString());
        } else if (problem.anchors.size() < 2) {
            result = Result.failure("not_enough_anchors", false);
        } else {
            long deadline = System.currentTimeMillis() + settings.timeoutMs;
            result = solve(problem, settings.mode, deadline, settings.beamWidth);
            if (!result.success && !cancelled.getAsBoolean() && settings.mode == Config.SolveMode.WEIGHTED) {
                long normalDeadline = System.currentTimeMillis()
                    + Math.max(1000, Math.min(3000, settings.timeoutMs / 2));
                Result normal = solve(
                    problem,
                    Config.SolveMode.NORMAL,
                    normalDeadline,
                    Math.max(settings.beamWidth, NORMAL_RETRY_BEAM));
                if (normal.success) result = normal.withFallback(true);
            }
            if (!result.success && !cancelled.getAsBoolean()) {
                Result steiner = solveProductSteiner(problem, settings.mode, deadline);
                if (steiner.success) {
                    result = steiner.withFallback(true);
                } else if (!steiner.timedOut) {
                    long treeDeadline = System.currentTimeMillis()
                        + Math.max(1000, Math.min(5000, settings.timeoutMs * 2));
                    Result tree = solvePhysicalTree(problem, treeDeadline);
                    if (tree.success) result = tree.withFallback(true);
                } else {
                    result = steiner;
                }
            }
        }
        result = result.withDiagnostics(elapsedMillis(started), metrics, false);
        if (logFailure && !result.success && !cancelled.getAsBoolean()) {
            logUnsolvedNote(note, result.failureReason, problem.graph);
        }
        if (result.success && !cancelled.getAsBoolean()) {
            synchronized (RESULT_CACHE) {
                RESULT_CACHE.put(cacheKey, result);
            }
        }
        return result;
    }

    public static RepairPlan findRepairPlan(ResearchNoteData note, AspectList inventory, Config.SolverSettings settings,
        BooleanSupplier cancelled, Result initialResult, int maxRepairs) {
        if (note == null || maxRepairs <= 0
            || initialResult == null
            || initialResult.success
            || !"incompatible_corridor".equals(initialResult.failureReason)) return null;
        long deadline = System.nanoTime() + REPAIR_PLAN_TIMEOUT_MS * 1_000_000L;
        BooleanSupplier repairCancelled = () -> cancelled.getAsBoolean() || System.nanoTime() >= deadline;
        List<RepairSearchNode> frontier = Collections.singletonList(
            new RepairSearchNode(ResearchNoteSnapshot.copyOf(note), new ArrayList<>(), initialResult.failureReason));
        Set<String> visited = new HashSet<>();
        visited.add(ResearchNoteFingerprint.state(note));
        for (int depth = 0; depth < maxRepairs && !frontier.isEmpty(); depth++) {
            List<RepairSearchNode> next = new ArrayList<>();
            for (RepairSearchNode node : frontier) {
                if (repairCancelled.getAsBoolean()) return null;
                Problem problem = Problem.from(node.note, inventory, settings, repairCancelled, new Metrics());
                for (ForcedCorridorFailure candidate : repairCandidates(problem, node.failureReason)) {
                    ResearchNoteData repaired = ResearchNoteSnapshot.copyOf(node.note);
                    ResearchManager.HexEntry entry = repaired.hexEntries.get(candidate.repairCell.toString());
                    if (entry == null || entry.type < 1 || entry.aspect == null) continue;
                    repaired.hexEntries.put(candidate.repairCell.toString(), new ResearchManager.HexEntry(null, 0));
                    if (!visited.add(ResearchNoteFingerprint.state(repaired))) continue;
                    List<String> repairCells = new ArrayList<>(node.repairCells);
                    repairCells.add(candidate.repairCell.toString());
                    Result result = solve(repaired, inventory, settings, repairCancelled, false);
                    if (result.success && validateSolution(repaired, result.placements)) {
                        TCAutoResearch.LOG.info(
                            "Verified research repair plan key={} cells={} placements={} solveMs={}",
                            note.key,
                            repairCells,
                            result.placements.size(),
                            result.solveTimeMs);
                        return new RepairPlan(repairCells, result);
                    }
                    if ("incompatible_corridor".equals(result.failureReason) && repairCells.size() < maxRepairs) {
                        next.add(new RepairSearchNode(repaired, repairCells, result.failureReason));
                    }
                }
            }
            frontier = next;
        }
        return null;
    }

    private static List<ForcedCorridorFailure> repairCandidates(Problem problem, String failureReason) {
        List<ForcedCorridorFailure> forced = forcedCorridorFailures(problem, false);
        return "incompatible_corridor".equals(failureReason) ? forced : Collections.emptyList();
    }

    private static void logUnsolvedNote(ResearchNoteData note, String reason, CompatibilityGraph graph) {
        if (note == null) return;
        List<String> cells = new ArrayList<>();
        for (Map.Entry<String, ResearchManager.HexEntry> entry : note.hexEntries.entrySet()) {
            ResearchManager.HexEntry value = entry.getValue();
            cells.add(
                entry.getKey() + "[type="
                    + value.type
                    + ",aspect="
                    + (value.aspect == null ? "" : value.aspect.getTag())
                    + "]");
        }
        Collections.sort(cells);
        TCAutoResearch.LOG.warn(
            "Unsolved research note key={} reason={} cells={} aspectGraph={}",
            note.key,
            reason,
            cells,
            graph == null ? "" : graph.description);
    }

    private static Result solve(Problem problem, Config.SolveMode mode, long deadline, int beamWidth) {
        List<Plan> frontier = new ArrayList<>();
        for (Map.Entry<Cell, Aspect> anchor : problem.anchors.entrySet()) {
            Map<Cell, Aspect> tree = new LinkedHashMap<>();
            tree.put(anchor.getKey(), anchor.getValue());
            Set<Cell> connected = new LinkedHashSet<>();
            connected.add(anchor.getKey());
            frontier.add(new Plan(tree, connected, 0));
        }
        frontier.sort(Plan.ORDER);
        frontier = trim(frontier, beamWidth);
        problem.metrics.peakPlans = Math.max(problem.metrics.peakPlans, frontier.size());

        boolean timedOut = false;
        while (!frontier.isEmpty() && frontier.get(0).connected.size() < problem.anchors.size()) {
            if (problem.cancelled.getAsBoolean()) return Result.failure("cancelled", false);
            if (System.currentTimeMillis() >= deadline) {
                timedOut = true;
                break;
            }

            List<Plan> next = new ArrayList<>();
            for (Plan plan : frontier) {
                for (Map.Entry<Cell, Aspect> anchor : problem.anchors.entrySet()) {
                    if (plan.connected.contains(anchor.getKey())) continue;
                    Route route = findRoute(problem, plan, anchor.getKey(), anchor.getValue(), mode, deadline);
                    if (route == null) continue;
                    Map<Cell, Aspect> tree = new LinkedHashMap<>(plan.tree);
                    tree.putAll(route.assignments);
                    Set<Cell> connected = new LinkedHashSet<>(plan.connected);
                    connected.addAll(route.connectedAnchors);
                    next.add(new Plan(tree, connected, plan.cost + route.cost));
                }
            }
            if (next.isEmpty()) break;
            problem.metrics.peakPlans = Math.max(problem.metrics.peakPlans, next.size());
            frontier = deduplicateAndTrim(next, beamWidth);
        }

        List<Result> candidates = new ArrayList<>();
        for (Plan plan : frontier) {
            if (plan.connected.size() != problem.anchors.size()) continue;
            Map<String, Aspect> placements = new TreeMap<>();
            for (Map.Entry<Cell, Aspect> entry : plan.tree.entrySet()) {
                if (!problem.anchors.containsKey(entry.getKey())) {
                    placements.put(
                        entry.getKey()
                            .toString(),
                        entry.getValue());
                }
            }
            if (!validateSolution(problem.note, placements)) continue;
            Analysis analysis = analyzeInventory(placements.values(), problem.inventory);
            candidates.add(
                Result.success(
                    placements,
                    plan.cost,
                    analysis.synthesisOperations,
                    analysis.missingPrimals,
                    mode,
                    timedOut));
        }

        if (candidates.isEmpty()) return Result.failure(timedOut ? "timeout" : "no_path", timedOut);
        candidates.sort(Result.ORDER);
        return candidates.get(0);
    }

    /**
     * Mirrors the original AutoResearch solver's useful separation of concerns: first build a physical
     * connection tree, then assign aspects through that tree with backtracking. This prevents an early
     * shortest route from permanently choosing a junction aspect which only satisfies one branch.
     */
    private static Result solvePhysicalTree(Problem problem, long deadline) {
        List<PhysicalPlan> frontier = new ArrayList<>();
        for (Cell root : problem.anchors.keySet()) frontier.add(PhysicalPlan.root(root));
        frontier = trimPhysicalPlans(frontier);

        for (int depth = 1; depth < problem.anchors.size() && !frontier.isEmpty(); depth++) {
            if (problem.cancelled.getAsBoolean()) return Result.failure("cancelled", false);
            if (System.currentTimeMillis() >= deadline) return Result.failure("timeout", true);
            List<PhysicalPlan> next = new ArrayList<>();
            for (PhysicalPlan plan : frontier) {
                for (Cell anchor : problem.anchors.keySet()) {
                    if (plan.connected.contains(anchor)) continue;
                    for (List<Cell> path : findPhysicalPaths(problem, anchor, plan.cells)) {
                        next.add(plan.attach(path, problem.anchors));
                    }
                }
            }
            frontier = trimPhysicalPlans(next);
            Result result = labelPhysicalPlans(problem, frontier, deadline);
            if (result != null) return result;
        }
        return Result.failure(
            System.currentTimeMillis() >= deadline ? "timeout" : "no_path",
            System.currentTimeMillis() >= deadline);
    }

    private static Result labelPhysicalPlans(Problem problem, List<PhysicalPlan> plans, long deadline) {
        for (PhysicalPlan plan : plans) {
            if (plan.connected.size() != problem.anchors.size()) continue;
            Map<Cell, Aspect> assignments = new LinkedHashMap<>();
            Aspect root = problem.anchors.get(plan.root);
            if (root == null || !labelTree(problem, plan.branches, plan.root, null, root, assignments, deadline))
                continue;
            Plan labelled = new Plan(
                assignments,
                plan.connected,
                Math.max(0, assignments.size() - problem.anchors.size()));
            Result result = resultForPlan(problem, labelled, Config.SolveMode.NORMAL, false);
            if (result != null) return result;
        }
        return null;
    }

    private static List<PhysicalPlan> trimPhysicalPlans(List<PhysicalPlan> plans) {
        plans.sort(PhysicalPlan.ORDER);
        Map<String, PhysicalPlan> unique = new LinkedHashMap<>();
        for (PhysicalPlan plan : plans) {
            unique.putIfAbsent(plan.canonical, plan);
            if (unique.size() >= PHYSICAL_TREE_BEAM) break;
        }
        return new ArrayList<>(unique.values());
    }

    private static List<List<Cell>> findPhysicalPaths(Problem problem, Cell start, Set<Cell> tree) {
        List<Cell> primary = findPhysicalPath(problem, start, tree, Collections.emptySet());
        if (primary == null) return Collections.emptyList();
        List<List<Cell>> result = new ArrayList<>();
        result.add(primary);
        Set<String> seen = new HashSet<>();
        seen.add(primary.toString());
        for (Cell cell : primary) {
            if (result.size() >= PHYSICAL_PATH_ALTERNATIVES) break;
            if (tree.contains(cell) || problem.anchors.containsKey(cell)) continue;
            List<Cell> alternate = findPhysicalPath(problem, start, tree, Collections.singleton(cell));
            if (alternate != null && seen.add(alternate.toString())) result.add(alternate);
        }
        return result;
    }

    /**
     * A degree-two corridor bounded by two fixed aspects cannot be repaired by branches elsewhere on the board.
     * Reject it before the expensive tree search when the aspect graph has no walk of precisely that length.
     */
    private static ForcedCorridorFailure forcedCorridorFailure(Problem problem, boolean log) {
        List<ForcedCorridorFailure> failures = forcedCorridorFailures(problem, log);
        return failures.isEmpty() ? null : failures.get(0);
    }

    private static List<ForcedCorridorFailure> forcedCorridorFailures(Problem problem, boolean log) {
        Map<Cell, ForcedCorridorFailure> candidates = new HashMap<>();
        Set<String> seenCorridors = new HashSet<>();
        for (Map.Entry<Cell, Aspect> anchor : problem.anchors.entrySet()) {
            Integer startAspect = problem.graph.aspectIndices.get(anchor.getValue());
            Integer startCell = problem.cellIndices.get(anchor.getKey());
            if (startAspect == null || startCell == null) continue;
            for (int firstNeighbor : problem.neighbors[startCell]) {
                int previous = startCell;
                int current = firstNeighbor;
                int steps = 1;
                while (problem.anchorAspects[current] < 0 && problem.neighbors[current].length == 2) {
                    int next = problem.neighbors[current][0] == previous ? problem.neighbors[current][1]
                        : problem.neighbors[current][0];
                    previous = current;
                    current = next;
                    steps++;
                }
                int endAspect = problem.anchorAspects[current];
                if (endAspect >= 0 && !hasAspectWalk(problem.graph, startAspect, endAspect, steps)) {
                    Cell endCell = problem.cellsByIndex[current];
                    String corridorKey = anchor.getKey()
                        .toString()
                        .compareTo(endCell.toString()) <= 0 ? anchor.getKey() + "|" + endCell
                            : endCell + "|" + anchor.getKey();
                    if (!seenCorridors.add(corridorKey)) continue;
                    candidates.put(
                        anchor.getKey(),
                        new ForcedCorridorFailure(anchor.getKey(), problem.neighbors[startCell].length));
                    candidates.put(endCell, new ForcedCorridorFailure(endCell, problem.neighbors[current].length));
                    if (log) {
                        TCAutoResearch.LOG.warn(
                            "Research note key={} has incompatible forced corridor {}({}) -> {}({}) steps={}",
                            problem.note.key,
                            anchor.getKey(),
                            anchor.getValue()
                                .getTag(),
                            endCell,
                            problem.graph.aspects[endAspect].getTag(),
                            steps);
                    }
                }
            }
        }
        List<ForcedCorridorFailure> result = new ArrayList<>(candidates.values());
        result.sort(ForcedCorridorFailure.ORDER);
        return result;
    }

    private static final class ForcedCorridorFailure {

        static final Comparator<ForcedCorridorFailure> ORDER = Comparator
            .comparingInt((ForcedCorridorFailure failure) -> failure.degree)
            .thenComparing(failure -> failure.repairCell.toString());

        final Cell repairCell;
        final int degree;

        private ForcedCorridorFailure(Cell repairCell, int degree) {
            this.repairCell = repairCell;
            this.degree = degree;
        }
    }

    private static final class RepairSearchNode {

        final ResearchNoteData note;
        final List<String> repairCells;
        final String failureReason;

        private RepairSearchNode(ResearchNoteData note, List<String> repairCells, String failureReason) {
            this.note = note;
            this.repairCells = repairCells;
            this.failureReason = failureReason;
        }
    }

    private static boolean hasAspectWalk(CompatibilityGraph graph, int start, int target, int steps) {
        boolean[] reachable = new boolean[graph.aspects.length];
        reachable[start] = true;
        for (int step = 0; step < steps; step++) {
            boolean[] next = new boolean[graph.aspects.length];
            for (int aspect = 0; aspect < reachable.length; aspect++) {
                if (!reachable[aspect]) continue;
                for (int neighbor : graph.compatibleIds[aspect]) next[neighbor] = true;
            }
            reachable = next;
        }
        return reachable[target];
    }

    private static List<Cell> findPhysicalPath(Problem problem, Cell start, Set<Cell> tree, Set<Cell> blocked) {
        Integer startId = problem.cellIndices.get(start);
        if (startId == null) return null;
        problem.metrics.routeSearches++;
        int[] previous = new int[problem.cellsByIndex.length];
        Arrays.fill(previous, -1);
        boolean[] visited = new boolean[problem.cellsByIndex.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(startId);
        visited[startId] = true;
        int end = -1;
        while (!queue.isEmpty()) {
            if (problem.cancelled.getAsBoolean()) return null;
            int current = queue.removeFirst();
            if (current != startId && tree.contains(problem.cellsByIndex[current])) {
                end = current;
                break;
            }
            for (int neighbor : problem.neighbors[current]) {
                if (visited[neighbor] || blocked.contains(problem.cellsByIndex[neighbor])) continue;
                visited[neighbor] = true;
                previous[neighbor] = current;
                queue.addLast(neighbor);
            }
        }
        if (end < 0) return null;
        List<Cell> path = new ArrayList<>();
        for (int current = end; current >= 0; current = previous[current]) path.add(problem.cellsByIndex[current]);
        Collections.reverse(path);
        return path;
    }

    private static boolean labelTree(Problem problem, Map<Cell, List<Cell>> branches, Cell cell, Cell parent,
        Aspect aspect, Map<Cell, Aspect> assignments, long deadline) {
        if (problem.cancelled.getAsBoolean() || System.currentTimeMillis() >= deadline) return false;
        Aspect anchor = problem.anchors.get(cell);
        if (anchor != null && anchor != aspect) return false;
        Map<Cell, Aspect> before = new LinkedHashMap<>(assignments);
        assignments.put(cell, aspect);
        List<Cell> children = new ArrayList<>();
        for (Cell child : branches.get(cell)) if (!child.equals(parent)) children.add(child);
        children.sort(Comparator.comparing(child -> problem.anchors.containsKey(child) ? 0 : 1));
        for (Cell child : children) {
            Aspect childAnchor = problem.anchors.get(child);
            if (childAnchor != null) {
                if (!compatible(aspect, childAnchor)
                    || !labelTree(problem, branches, child, cell, childAnchor, assignments, deadline)) {
                    assignments.clear();
                    assignments.putAll(before);
                    return false;
                }
                continue;
            }
            Integer aspectId = problem.graph.aspectIndices.get(aspect);
            boolean assigned = false;
            if (aspectId != null) {
                for (int candidate : problem.graph.compatibleIds[aspectId]) {
                    if (problem.disabledAspects[candidate]) continue;
                    if (labelTree(
                        problem,
                        branches,
                        child,
                        cell,
                        problem.graph.aspects[candidate],
                        assignments,
                        deadline)) {
                        assigned = true;
                        break;
                    }
                }
            }
            if (!assigned) {
                assignments.clear();
                assignments.putAll(before);
                return false;
            }
        }
        return true;
    }

    /**
     * Exact multi-terminal dynamic programming on the board/aspect product graph. It covers branch topologies which
     * a sequence of pairwise shortest paths can prune away. The reconstructed product tree is projected back onto
     * board cells and must still pass the same final validation used before any placement is sent.
     */
    private static Result solveProductSteiner(Problem problem, Config.SolveMode mode, long deadline) {
        int terminalCount = problem.anchors.size();
        if (terminalCount < 2 || terminalCount > STEINER_MAX_ANCHORS) return Result.failure("no_path", false);
        int aspectCount = problem.graph.aspects.length;
        int stateCount = problem.cellsByIndex.length * aspectCount;
        int maskCount = 1 << terminalCount;
        long[][] distance = new long[maskCount][stateCount];
        byte[][] parents = new byte[maskCount][stateCount];
        int[][] parentData = new int[maskCount][stateCount];
        for (int mask = 0; mask < maskCount; mask++) Arrays.fill(distance[mask], Long.MAX_VALUE);

        int terminal = 0;
        for (Map.Entry<Cell, Aspect> anchor : problem.anchors.entrySet()) {
            Integer cell = problem.cellIndices.get(anchor.getKey());
            Integer aspect = problem.graph.aspectIndices.get(anchor.getValue());
            if (cell == null || aspect == null) return Result.failure("no_path", false);
            int state = cell * aspectCount + aspect;
            distance[1 << terminal][state] = 0;
            parents[1 << terminal][state] = SteinerWorkspace.LEAF;
            terminal++;
        }

        IntLongHeap queue = new IntLongHeap(Math.min(128, stateCount));
        for (int mask = 1; mask < maskCount; mask++) {
            if (problem.cancelled.getAsBoolean()) return Result.failure("cancelled", false);
            if (System.currentTimeMillis() >= deadline) return Result.failure("timeout", true);
            long[] current = distance[mask];
            byte[] currentParents = parents[mask];
            int[] currentParentData = parentData[mask];
            if ((mask & (mask - 1)) != 0) {
                for (int leftMask = (mask - 1) & mask; leftMask > 0; leftMask = (leftMask - 1) & mask) {
                    int rightMask = mask ^ leftMask;
                    if (leftMask > rightMask) continue;
                    long[] left = distance[leftMask];
                    long[] right = distance[rightMask];
                    for (int state = 0; state < stateCount; state++) {
                        if (left[state] == Long.MAX_VALUE || right[state] == Long.MAX_VALUE) continue;
                        long merged = left[state] + right[state];
                        if (merged < current[state]) {
                            current[state] = merged;
                            currentParents[state] = SteinerWorkspace.MERGE;
                            currentParentData[state] = leftMask;
                        }
                    }
                }
            }

            queue.clear();
            int discovered = 0;
            for (int state = 0; state < stateCount; state++) {
                if (current[state] == Long.MAX_VALUE) continue;
                queue.add(state, current[state]);
                discovered++;
            }
            problem.metrics.observe(discovered, queue.size(), stateCount);
            int checks = 0;
            while (!queue.isEmpty()) {
                if ((checks++ & 255) == 0) {
                    if (problem.cancelled.getAsBoolean()) return Result.failure("cancelled", false);
                    if (System.currentTimeMillis() >= deadline) return Result.failure("timeout", true);
                }
                int state = queue.peekState();
                long cost = queue.peekCost();
                queue.remove();
                if (current[state] != cost) continue;
                problem.metrics.expandedStates++;
                int cell = state / aspectCount;
                int aspect = state % aspectCount;
                for (int neighbor : problem.neighbors[cell]) {
                    int fixed = problem.anchorAspects[neighbor];
                    if (fixed >= 0) {
                        if (!problem.graph.compatible[aspect][fixed]) continue;
                        int next = neighbor * aspectCount + fixed;
                        long nextCost = cost + edgeCost(problem, aspect, fixed, mode);
                        if (nextCost < current[next]) {
                            if (current[next] == Long.MAX_VALUE) discovered++;
                            current[next] = nextCost;
                            currentParents[next] = SteinerWorkspace.PATH;
                            currentParentData[next] = state;
                            queue.add(next, nextCost);
                        }
                        continue;
                    }
                    for (int candidate : problem.graph.compatibleIds[aspect]) {
                        if (problem.disabledAspects[candidate]) continue;
                        int next = neighbor * aspectCount + candidate;
                        long nextCost = cost + edgeCost(problem, aspect, candidate, mode);
                        if (nextCost >= current[next]) continue;
                        if (current[next] == Long.MAX_VALUE) discovered++;
                        current[next] = nextCost;
                        currentParents[next] = SteinerWorkspace.PATH;
                        currentParentData[next] = state;
                        queue.add(next, nextCost);
                    }
                }
                problem.metrics.observe(discovered, queue.size(), stateCount);
            }
        }

        int completeMask = maskCount - 1;
        long[] complete = distance[completeMask];
        long[] candidates = sortedFiniteStates(complete);
        for (long candidate : candidates) {
            int state = unpackState(candidate, stateCount);
            long candidateCost = complete[state];
            if (problem.cancelled.getAsBoolean()) return Result.failure("cancelled", false);
            if (System.currentTimeMillis() >= deadline) return Result.failure("timeout", true);
            Map<Cell, Aspect> tree = new LinkedHashMap<>();
            if (!collectSteinerTree(
                problem,
                completeMask,
                state,
                aspectCount,
                parents,
                parentData,
                tree,
                new HashSet<>())) continue;
            Map<String, Aspect> placements = new TreeMap<>();
            for (Map.Entry<Cell, Aspect> entry : tree.entrySet()) {
                if (!problem.anchors.containsKey(entry.getKey())) placements.put(
                    entry.getKey()
                        .toString(),
                    entry.getValue());
            }
            if (!validateSolution(problem.note, placements)) continue;
            Analysis analysis = analyzeInventory(placements.values(), problem.inventory);
            return Result
                .success(placements, candidateCost, analysis.synthesisOperations, analysis.missingPrimals, mode, false);
        }
        return Result.failure("no_path", false);
    }

    private static long[] sortedFiniteStates(long[] costs) {
        int count = 0;
        long maximum = 0;
        for (long cost : costs) {
            if (cost == Long.MAX_VALUE) continue;
            count++;
            maximum = Math.max(maximum, cost);
        }
        int stateBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, costs.length - 1)));
        if (maximum > (Long.MAX_VALUE >>> stateBits)) return sortedFiniteStatesWithHeap(costs);
        long[] candidates = new long[count];
        int index = 0;
        for (int state = 0; state < costs.length; state++) {
            long cost = costs[state];
            if (cost != Long.MAX_VALUE) candidates[index++] = (cost << stateBits) | state;
        }
        Arrays.sort(candidates);
        return candidates;
    }

    private static long[] sortedFiniteStatesWithHeap(long[] costs) {
        IntLongHeap heap = new IntLongHeap(Math.min(128, costs.length));
        int count = 0;
        for (int state = 0; state < costs.length; state++) {
            if (costs[state] == Long.MAX_VALUE) continue;
            heap.add(state, costs[state]);
            count++;
        }
        long[] candidates = new long[count];
        for (int index = 0; index < count; index++) {
            candidates[index] = heap.peekState();
            heap.remove();
        }
        return candidates;
    }

    private static int unpackState(long candidate, int stateCount) {
        int stateBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, stateCount - 1)));
        return (int) (candidate & ((1L << stateBits) - 1));
    }

    private static boolean collectSteinerTree(Problem problem, int mask, int state, int aspectCount, byte[][] parents,
        int[][] parentData, Map<Cell, Aspect> tree, Set<Integer> visited) {
        int identity = mask * (problem.cellsByIndex.length * aspectCount) + state;
        if (!visited.add(identity)) return true;
        byte parent = parents[mask][state];
        if (parent == SteinerWorkspace.MERGE) {
            int leftMask = parentData[mask][state];
            return collectSteinerTree(problem, leftMask, state, aspectCount, parents, parentData, tree, visited)
                && collectSteinerTree(problem, mask ^ leftMask, state, aspectCount, parents, parentData, tree, visited);
        }
        if (parent == SteinerWorkspace.PATH && !collectSteinerTree(
            problem,
            mask,
            parentData[mask][state],
            aspectCount,
            parents,
            parentData,
            tree,
            visited)) return false;
        if (parent == 0) return false;

        int cell = state / aspectCount;
        int aspect = state % aspectCount;
        int fixed = problem.anchorAspects[cell];
        if (fixed >= 0 && fixed != aspect) return false;
        Cell key = problem.cellsByIndex[cell];
        Aspect previous = tree.put(key, problem.graph.aspects[aspect]);
        return previous == null || previous == problem.graph.aspects[aspect];
    }

    private static Result resultForPlan(Problem problem, Plan plan, Config.SolveMode mode, boolean timedOut) {
        Map<String, Aspect> placements = new TreeMap<>();
        for (Map.Entry<Cell, Aspect> entry : plan.tree.entrySet()) {
            if (!problem.anchors.containsKey(entry.getKey())) placements.put(
                entry.getKey()
                    .toString(),
                entry.getValue());
        }
        if (!validateSolution(problem.note, placements)) return null;
        Analysis analysis = analyzeInventory(placements.values(), problem.inventory);
        return Result
            .success(placements, plan.cost, analysis.synthesisOperations, analysis.missingPrimals, mode, timedOut);
    }

    private static Route findRoute(Problem problem, Plan plan, Cell target, Aspect targetAspect, Config.SolveMode mode,
        long deadline) {
        problem.metrics.routeSearches++;
        Integer targetCell = problem.cellIndices.get(target);
        Integer targetAspectId = problem.graph.aspectIndices.get(targetAspect);
        if (targetCell == null || targetAspectId == null) return null;
        int aspectCount = problem.graph.aspects.length;
        int stateCount = problem.cellsByIndex.length * aspectCount;
        long[] distance = problem.distance;
        int[] previous = problem.previous;
        int[] fixedAspects = problem.fixedAspects;
        Arrays.fill(distance, Long.MAX_VALUE);
        Arrays.fill(previous, -1);
        Arrays.fill(fixedAspects, -1);
        for (Map.Entry<Cell, Aspect> entry : plan.tree.entrySet()) {
            Integer cellId = problem.cellIndices.get(entry.getKey());
            Integer aspectId = problem.graph.aspectIndices.get(entry.getValue());
            if (cellId != null && aspectId != null) fixedAspects[cellId] = aspectId;
        }

        int start = targetCell * aspectCount + targetAspectId;
        IntLongHeap queue = problem.queue;
        queue.clear();
        distance[start] = 0;
        queue.add(start, 0);
        int discovered = 1;
        problem.metrics.observe(discovered, queue.size(), stateCount);

        int checks = 0;
        while (!queue.isEmpty()) {
            if ((checks++ & 255) == 0 && (problem.cancelled.getAsBoolean() || System.currentTimeMillis() >= deadline))
                return null;
            int state = queue.peekState();
            long cost = queue.peekCost();
            queue.remove();
            if (distance[state] != cost) continue;
            problem.metrics.expandedStates++;

            int cellId = state / aspectCount;
            int aspectId = state % aspectCount;
            if (fixedAspects[cellId] >= 0) {
                Route route = unwind(problem, state, cost, previous, fixedAspects);
                if (route != null) return route;
                continue;
            }

            for (int neighbor : problem.neighbors[cellId]) {
                int fixedAspect = fixedAspects[neighbor];
                int anchorAspect = problem.anchorAspects[neighbor];
                int requiredAspect = fixedAspect >= 0 ? fixedAspect : anchorAspect;
                if (requiredAspect >= 0) {
                    if (!problem.graph.compatible[aspectId][requiredAspect]) continue;
                    long nextCost = cost + edgeCost(problem, aspectId, requiredAspect, mode);
                    int next = neighbor * aspectCount + requiredAspect;
                    if (nextCost < distance[next]) {
                        if (distance[next] == Long.MAX_VALUE) discovered++;
                        distance[next] = nextCost;
                        previous[next] = state;
                        queue.add(next, nextCost);
                    }
                    continue;
                }
                for (int candidate : problem.graph.compatibleIds[aspectId]) {
                    if (problem.disabledAspects[candidate]) continue;
                    long nextCost = cost + edgeCost(problem, aspectId, candidate, mode);
                    int next = neighbor * aspectCount + candidate;
                    if (nextCost >= distance[next]) continue;
                    if (distance[next] == Long.MAX_VALUE) discovered++;
                    distance[next] = nextCost;
                    previous[next] = state;
                    queue.add(next, nextCost);
                }
            }
            problem.metrics.observe(discovered, queue.size(), stateCount);
        }
        return null;
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000L);
    }

    private static long edgeCost(Problem problem, int leftId, int rightId, Config.SolveMode mode) {
        if (mode == Config.SolveMode.NORMAL) return 1;
        long cost = (long) problem.aspectCosts[leftId] + problem.aspectCosts[rightId];
        if (problem.settings.inventoryAware && problem.inventoryAmounts[rightId] <= 0) {
            cost += Math.max(1, problem.aspectCosts[rightId]);
        }
        return cost;
    }

    private static Route unwind(Problem problem, int end, long cost, int[] previous, int[] fixedAspects) {
        Map<Cell, Aspect> reversed = new LinkedHashMap<>();
        Set<Cell> connectedAnchors = new LinkedHashSet<>();
        boolean[] visitedCells = new boolean[problem.cellsByIndex.length];
        int aspectCount = problem.graph.aspects.length;
        int cursor = end;
        while (cursor >= 0) {
            int cellId = cursor / aspectCount;
            int aspectId = cursor % aspectCount;
            if (visitedCells[cellId]) return null;
            visitedCells[cellId] = true;
            Cell cell = problem.cellsByIndex[cellId];
            if (problem.anchors.containsKey(cell)) connectedAnchors.add(cell);
            if (fixedAspects[cellId] < 0) {
                reversed.put(cell, problem.graph.aspects[aspectId]);
            }
            cursor = previous[cursor];
        }
        List<Map.Entry<Cell, Aspect>> entries = new ArrayList<>(reversed.entrySet());
        Collections.reverse(entries);
        Map<Cell, Aspect> assignments = new LinkedHashMap<>();
        for (Map.Entry<Cell, Aspect> entry : entries) assignments.put(entry.getKey(), entry.getValue());
        return new Route(assignments, connectedAnchors, cost);
    }

    static boolean validateSolution(ResearchNoteData note, Map<String, Aspect> placements) {
        if (note == null) return false;
        Map<Cell, Aspect> board = new HashMap<>();
        Set<Cell> required = new HashSet<>();
        for (Map.Entry<String, ResearchManager.HexEntry> entry : note.hexEntries.entrySet()) {
            ResearchManager.HexEntry hex = entry.getValue();
            Aspect aspect = hex.aspect;
            if (hex.type < 1 || aspect == null) continue;
            Cell cell = Cell.parse(entry.getKey());
            board.put(cell, aspect);
            required.add(cell);
        }
        for (Map.Entry<String, Aspect> placement : placements.entrySet()) {
            ResearchManager.HexEntry current = note.hexEntries.get(placement.getKey());
            if (current == null || placement.getValue() == null
                || current.aspect != null && current.aspect != placement.getValue()) return false;
            Cell cell = Cell.parse(placement.getKey());
            board.put(cell, placement.getValue());
            required.add(cell);
        }
        if (required.size() < 2) return false;

        Set<Cell> visited = new HashSet<>();
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        Cell start = required.iterator()
            .next();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            Cell cell = queue.removeFirst();
            Aspect aspect = board.get(cell);
            for (Cell neighbor : cell.neighbors()) {
                Aspect neighborAspect = board.get(neighbor);
                if (neighborAspect != null && !visited.contains(neighbor) && compatible(aspect, neighborAspect)) {
                    visited.add(neighbor);
                    queue.addLast(neighbor);
                }
            }
        }
        return visited.containsAll(required);
    }

    private static boolean compatible(Aspect left, Aspect right) {
        if (left == null || right == null) return false;
        Aspect[] components = left.getComponents();
        if (components != null && (components[0] == right || components[1] == right)) return true;
        components = right.getComponents();
        return components != null && (components[0] == left || components[1] == left);
    }

    private static List<Plan> deduplicateAndTrim(List<Plan> plans, int limit) {
        plans.sort(Plan.ORDER);
        Map<String, Plan> unique = new LinkedHashMap<>();
        for (Plan plan : plans) {
            unique.putIfAbsent(plan.canonical, plan);
            if (unique.size() >= limit) break;
        }
        return new ArrayList<>(unique.values());
    }

    private static <T> List<T> trim(List<T> values, int limit) {
        return values.size() <= limit ? values : new ArrayList<>(values.subList(0, limit));
    }

    private static Analysis analyzeInventory(Collection<Aspect> placements, AspectList inventory) {
        Map<Aspect, Integer> available = new HashMap<>();
        for (Object value : Aspect.aspects.values()) {
            Aspect aspect = (Aspect) value;
            available.put(aspect, inventory.getAmount(aspect));
        }
        Map<Aspect, Integer> required = new HashMap<>();
        for (Aspect aspect : placements) required.put(aspect, required.getOrDefault(aspect, 0) + 1);
        List<Aspect> order = new ArrayList<>(required.keySet());
        order.sort(
            Comparator.comparingInt(WeightedResearchSolver::depth)
                .reversed()
                .thenComparing(Aspect::getTag));

        Analysis analysis = new Analysis();
        for (Aspect aspect : order) {
            consume(aspect, required.get(aspect), available, analysis, new HashSet<>());
        }
        return analysis;
    }

    private static void consume(Aspect aspect, int amount, Map<Aspect, Integer> available, Analysis analysis,
        Set<Aspect> stack) {
        if (amount <= 0) return;
        int present = available.getOrDefault(aspect, 0);
        int used = Math.min(present, amount);
        available.put(aspect, present - used);
        int remaining = amount - used;
        if (remaining <= 0) return;

        Aspect[] components = aspect.getComponents();
        if (components == null || !stack.add(aspect)) {
            analysis.missingPrimals
                .put(aspect.getTag(), analysis.missingPrimals.getOrDefault(aspect.getTag(), 0) + remaining);
            return;
        }
        analysis.synthesisOperations += remaining;
        consume(components[0], remaining, available, analysis, stack);
        consume(components[1], remaining, available, analysis, stack);
        stack.remove(aspect);
    }

    private static int depth(Aspect aspect) {
        Aspect[] components = aspect.getComponents();
        return components == null ? 0 : 1 + Math.max(depth(components[0]), depth(components[1]));
    }

    /**
     * Uses the live registry after every add-on has registered its aspects. Anchors and their components are
     * collected as well because a few older add-ons expose an Aspect instance before adding it to the registry.
     */
    private static synchronized CompatibilityGraph compatibility(Metrics metrics, Collection<Aspect> requiredAspects) {
        int registrySize = Aspect.aspects.size();
        if (compatibilityGraph != null && compatibilityGraph.registrySize == registrySize) {
            boolean containsRequired = true;
            for (Aspect required : requiredAspects) {
                if (!compatibilityGraph.aspectIndices.containsKey(required)) {
                    containsRequired = false;
                    break;
                }
            }
            if (containsRequired) {
                metrics.graphCacheHit = true;
                return compatibilityGraph;
            }
        }
        List<Aspect> aspects = new ArrayList<>();
        Set<Aspect> collected = Collections.newSetFromMap(new IdentityHashMap<Aspect, Boolean>());
        for (Object value : Aspect.aspects.values()) {
            if (value instanceof Aspect) collectAspect((Aspect) value, collected, aspects);
        }
        for (Aspect aspect : requiredAspects) collectAspect(aspect, collected, aspects);
        aspects.sort(Comparator.comparing(Aspect::getTag));
        StringBuilder signature = new StringBuilder();
        for (Aspect aspect : aspects) {
            signature.append(aspect.getTag())
                .append('=');
            Aspect[] components = aspect.getComponents();
            if (components != null) {
                signature.append(components[0].getTag())
                    .append('+')
                    .append(components[1].getTag());
            }
            signature.append(';');
        }
        String currentSignature = signature.toString();
        if (compatibilityGraph != null && compatibilityGraph.signature.equals(currentSignature)) {
            metrics.graphCacheHit = true;
            return compatibilityGraph;
        }

        synchronized (RESULT_CACHE) {
            RESULT_CACHE.clear();
        }

        Aspect[] aspectsById = aspects.toArray(new Aspect[0]);
        Map<Aspect, Integer> aspectIndices = new IdentityHashMap<>();
        for (int i = 0; i < aspectsById.length; i++) aspectIndices.put(aspectsById[i], i);
        boolean[][] compatible = new boolean[aspectsById.length][aspectsById.length];
        for (Aspect aspect : aspectsById) {
            Aspect[] components = aspect.getComponents();
            if (components == null) continue;
            Integer aspectId = aspectIndices.get(aspect);
            Integer firstId = aspectIndices.get(components[0]);
            Integer secondId = aspectIndices.get(components[1]);
            if (firstId != null) compatible[aspectId][firstId] = compatible[firstId][aspectId] = true;
            if (secondId != null) compatible[aspectId][secondId] = compatible[secondId][aspectId] = true;
        }
        int[][] compatibleIds = new int[aspectsById.length][];
        for (int i = 0; i < compatible.length; i++) {
            int count = 0;
            for (boolean value : compatible[i]) if (value) count++;
            compatibleIds[i] = new int[count];
            int index = 0;
            for (int j = 0; j < compatible[i].length; j++) {
                if (compatible[i][j]) compatibleIds[i][index++] = j;
            }
        }
        List<String> definitions = new ArrayList<>();
        for (Aspect aspect : aspectsById) {
            Aspect[] components = aspect.getComponents();
            definitions.add(
                aspect.getTag() + "="
                    + (components == null ? "" : components[0].getTag() + "+" + components[1].getTag()));
        }
        compatibilityGraph = new CompatibilityGraph(
            currentSignature,
            registrySize,
            aspectsById,
            Collections.unmodifiableMap(aspectIndices),
            compatibleIds,
            compatible,
            String.join(";", definitions));
        return compatibilityGraph;
    }

    private static BoardGeometry geometry(ResearchNoteData note, Metrics metrics) {
        String[] keys = note.hexEntries.keySet()
            .toArray(new String[0]);
        Arrays.sort(keys);
        String signature = String.join(";", keys);
        synchronized (GEOMETRY_CACHE) {
            BoardGeometry cached = GEOMETRY_CACHE.get(signature);
            if (cached != null) {
                metrics.geometryCacheHit = true;
                return cached;
            }
        }

        Cell[] cells = new Cell[keys.length];
        Map<String, Cell> cellsByKey = new HashMap<>();
        for (int i = 0; i < keys.length; i++) {
            Cell cell = Cell.parse(keys[i]);
            cells[i] = cell;
            cellsByKey.put(keys[i], cell);
        }
        Arrays.sort(
            cells,
            Comparator.comparingInt((Cell cell) -> cell.q)
                .thenComparingInt(cell -> cell.r));
        Map<Cell, Integer> indices = new HashMap<>();
        for (int i = 0; i < cells.length; i++) indices.put(cells[i], i);
        int[][] neighbors = new int[cells.length][];
        for (int i = 0; i < cells.length; i++) {
            Cell cell = cells[i];
            int[] adjacent = new int[6];
            int count = 0;
            for (Cell candidate : cell.neighbors()) {
                Integer neighbor = indices.get(candidate);
                if (neighbor != null) adjacent[count++] = neighbor;
            }
            neighbors[i] = Arrays.copyOf(adjacent, count);
        }
        BoardGeometry built = new BoardGeometry(
            cells,
            Collections.unmodifiableMap(indices),
            Collections.unmodifiableMap(cellsByKey),
            neighbors);
        synchronized (GEOMETRY_CACHE) {
            BoardGeometry concurrent = GEOMETRY_CACHE.get(signature);
            if (concurrent != null) {
                metrics.geometryCacheHit = true;
                return concurrent;
            }
            GEOMETRY_CACHE.put(signature, built);
        }
        return built;
    }

    private static void collectAspect(Aspect aspect, Set<Aspect> collected, List<Aspect> aspects) {
        if (aspect == null || !collected.add(aspect)) return;
        aspects.add(aspect);
        Aspect[] components = aspect.getComponents();
        if (components != null) {
            collectAspect(components[0], collected, aspects);
            collectAspect(components[1], collected, aspects);
        }
    }

    private static final class Problem {

        final ResearchNoteData note;
        final Map<Cell, Aspect> anchors;
        final AspectList inventory;
        final Config.SolverSettings settings;
        final BooleanSupplier cancelled;
        final Metrics metrics;
        final CompatibilityGraph graph;
        final Cell[] cellsByIndex;
        final Map<Cell, Integer> cellIndices;
        final int[][] neighbors;
        final int[] anchorAspects;
        final int[] inventoryAmounts;
        final int[] aspectCosts;
        final boolean[] disabledAspects;
        final long[] distance;
        final int[] previous;
        final int[] fixedAspects;
        final IntLongHeap queue;

        private Problem(ResearchNoteData note, Map<Cell, Aspect> anchors, AspectList inventory,
            Config.SolverSettings settings, BooleanSupplier cancelled, Metrics metrics, CompatibilityGraph graph,
            Cell[] cellsByIndex, Map<Cell, Integer> cellIndices, int[][] neighbors, int[] anchorAspects,
            int[] inventoryAmounts) {
            this.note = note;
            this.anchors = anchors;
            this.inventory = inventory;
            this.settings = settings;
            this.cancelled = cancelled;
            this.metrics = metrics;
            this.graph = graph;
            this.cellsByIndex = cellsByIndex;
            this.cellIndices = cellIndices;
            this.neighbors = neighbors;
            this.anchorAspects = anchorAspects;
            this.inventoryAmounts = inventoryAmounts;
            this.aspectCosts = new int[graph.aspects.length];
            this.disabledAspects = new boolean[graph.aspects.length];
            for (int i = 0; i < graph.aspects.length; i++) {
                String tag = graph.aspects[i].getTag();
                aspectCosts[i] = settings.cost(tag);
                disabledAspects[i] = settings.disabled(tag);
            }
            int stateCount = cellsByIndex.length * graph.aspects.length;
            this.distance = new long[stateCount];
            this.previous = new int[stateCount];
            this.fixedAspects = new int[cellsByIndex.length];
            this.queue = new IntLongHeap(Math.min(64, stateCount));
        }

        static Problem from(ResearchNoteData note, AspectList inventory, Config.SolverSettings settings,
            BooleanSupplier cancelled, Metrics metrics) {
            BoardGeometry geometry = geometry(note, metrics);
            Map<Cell, Aspect> anchors = new LinkedHashMap<>();
            for (Map.Entry<String, ResearchManager.HexEntry> entry : note.hexEntries.entrySet()) {
                Cell cell = geometry.cellsByKey.get(entry.getKey());
                if (entry.getValue().type >= 1 && entry.getValue().aspect != null) {
                    anchors.put(cell, entry.getValue().aspect);
                }
            }
            CompatibilityGraph graph = compatibility(metrics, anchors.values());
            int[] anchorAspects = new int[geometry.cells.length];
            Arrays.fill(anchorAspects, -1);
            for (Map.Entry<Cell, Aspect> anchor : anchors.entrySet()) {
                Integer cellId = geometry.indices.get(anchor.getKey());
                Integer aspectId = graph.aspectIndices.get(anchor.getValue());
                if (cellId != null && aspectId != null) anchorAspects[cellId] = aspectId;
            }
            int[] inventoryAmounts = new int[graph.aspects.length];
            for (int i = 0; i < graph.aspects.length; i++) inventoryAmounts[i] = inventory.getAmount(graph.aspects[i]);
            return new Problem(
                note,
                anchors,
                inventory,
                settings,
                cancelled,
                metrics,
                graph,
                geometry.cells,
                geometry.indices,
                geometry.neighbors,
                anchorAspects,
                inventoryAmounts);
        }

        String cacheKey() {
            StringBuilder key = new StringBuilder(settings.fingerprint());
            key.append("|board:");
            for (Cell cell : cellsByIndex) {
                Aspect anchor = anchors.get(cell);
                key.append(cell)
                    .append('=')
                    .append(anchor == null ? "" : anchor.getTag())
                    .append(';');
            }
            key.append("|inventory:");
            for (int i = 0; i < graph.aspects.length; i++) {
                key.append(graph.aspects[i].getTag())
                    .append('=')
                    .append(inventoryAmounts[i])
                    .append(';');
            }
            return key.toString();
        }
    }

    private static final class Plan {

        static final Comparator<Plan> ORDER = Comparator.comparingInt((Plan plan) -> -plan.connected.size())
            .thenComparingLong(plan -> plan.cost)
            .thenComparingInt(plan -> plan.tree.size())
            .thenComparing(plan -> plan.canonical);

        final Map<Cell, Aspect> tree;
        final Set<Cell> connected;
        final long cost;
        final String canonical;

        private Plan(Map<Cell, Aspect> tree, Set<Cell> connected, long cost) {
            this.tree = tree;
            this.connected = connected;
            this.cost = cost;
            List<String> values = new ArrayList<>();
            for (Map.Entry<Cell, Aspect> entry : tree.entrySet()) {
                values.add(
                    entry.getKey() + "="
                        + entry.getValue()
                            .getTag());
            }
            Collections.sort(values);
            this.canonical = String.join(";", values);
        }
    }

    private static final class PhysicalPlan {

        static final Comparator<PhysicalPlan> ORDER = Comparator
            .comparingInt((PhysicalPlan plan) -> -plan.connected.size())
            .thenComparingInt(plan -> plan.cells.size())
            .thenComparing(plan -> plan.canonical);

        final Cell root;
        final Set<Cell> cells;
        final Map<Cell, List<Cell>> branches;
        final Set<Cell> connected;
        final String canonical;

        private PhysicalPlan(Cell root, Set<Cell> cells, Map<Cell, List<Cell>> branches, Set<Cell> connected) {
            this.root = root;
            this.cells = cells;
            this.branches = branches;
            this.connected = connected;
            List<String> edges = new ArrayList<>();
            for (Map.Entry<Cell, List<Cell>> entry : branches.entrySet()) {
                for (Cell child : entry.getValue()) {
                    String left = entry.getKey()
                        .toString();
                    String right = child.toString();
                    edges.add(left.compareTo(right) < 0 ? left + "-" + right : right + "-" + left);
                }
            }
            Collections.sort(edges);
            this.canonical = root + "|" + String.join(";", edges);
        }

        static PhysicalPlan root(Cell root) {
            Set<Cell> cells = new LinkedHashSet<>();
            cells.add(root);
            Map<Cell, List<Cell>> branches = new LinkedHashMap<>();
            branches.put(root, new ArrayList<>());
            Set<Cell> connected = new LinkedHashSet<>();
            connected.add(root);
            return new PhysicalPlan(root, cells, branches, connected);
        }

        PhysicalPlan attach(List<Cell> path, Map<Cell, Aspect> anchors) {
            Set<Cell> nextCells = new LinkedHashSet<>(cells);
            nextCells.addAll(path);
            Map<Cell, List<Cell>> nextBranches = new LinkedHashMap<>();
            for (Map.Entry<Cell, List<Cell>> entry : branches.entrySet()) {
                nextBranches.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            for (Cell cell : path) nextBranches.putIfAbsent(cell, new ArrayList<>());
            for (int index = 1; index < path.size(); index++) {
                Cell previous = path.get(index - 1);
                Cell current = path.get(index);
                if (!nextBranches.get(previous)
                    .contains(current))
                    nextBranches.get(previous)
                        .add(current);
                if (!nextBranches.get(current)
                    .contains(previous))
                    nextBranches.get(current)
                        .add(previous);
            }
            Set<Cell> nextConnected = new LinkedHashSet<>(connected);
            for (Cell cell : path) if (anchors.containsKey(cell)) nextConnected.add(cell);
            return new PhysicalPlan(root, nextCells, nextBranches, nextConnected);
        }
    }

    private static final class Route {

        final Map<Cell, Aspect> assignments;
        final Set<Cell> connectedAnchors;
        final long cost;

        private Route(Map<Cell, Aspect> assignments, Set<Cell> connectedAnchors, long cost) {
            this.assignments = assignments;
            this.connectedAnchors = connectedAnchors;
            this.cost = cost;
        }
    }

    private static final class IntLongHeap {

        private int[] states;
        private long[] costs;
        private int size;

        private IntLongHeap(int capacity) {
            states = new int[Math.max(4, capacity)];
            costs = new long[states.length];
        }

        void add(int state, long cost) {
            ensureCapacity();
            int index = size++;
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                if (!less(cost, state, costs[parent], states[parent])) break;
                costs[index] = costs[parent];
                states[index] = states[parent];
                index = parent;
            }
            costs[index] = cost;
            states[index] = state;
        }

        int peekState() {
            return states[0];
        }

        long peekCost() {
            return costs[0];
        }

        void remove() {
            int last = --size;
            if (last <= 0) return;
            siftDown(0, states[last], costs[last]);
        }

        boolean isEmpty() {
            return size == 0;
        }

        void clear() {
            size = 0;
        }

        int size() {
            return size;
        }

        private void ensureCapacity() {
            if (size < states.length) return;
            int capacity = states.length * 2;
            states = Arrays.copyOf(states, capacity);
            costs = Arrays.copyOf(costs, capacity);
        }

        private void siftDown(int index, int state, long cost) {
            while (true) {
                int left = index * 2 + 1;
                if (left >= size) break;
                int right = left + 1;
                int child = right < size && less(costs[right], states[right], costs[left], states[left]) ? right : left;
                if (!less(costs[child], states[child], cost, state)) break;
                costs[index] = costs[child];
                states[index] = states[child];
                index = child;
            }
            costs[index] = cost;
            states[index] = state;
        }

        private static boolean less(long leftCost, int leftState, long rightCost, int rightState) {
            return leftCost < rightCost || leftCost == rightCost && leftState < rightState;
        }
    }

    private static final class Cell {

        final int q;
        final int r;

        private Cell(int q, int r) {
            this.q = q;
            this.r = r;
        }

        static Cell parse(String value) {
            int separator = value.indexOf(':');
            return new Cell(
                Integer.parseInt(value.substring(0, separator)),
                Integer.parseInt(value.substring(separator + 1)));
        }

        List<Cell> neighbors() {
            List<Cell> result = new ArrayList<>(6);
            result.add(new Cell(q + 1, r));
            result.add(new Cell(q - 1, r));
            result.add(new Cell(q, r + 1));
            result.add(new Cell(q, r - 1));
            result.add(new Cell(q + 1, r - 1));
            result.add(new Cell(q - 1, r + 1));
            return result;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Cell)) return false;
            Cell cell = (Cell) other;
            return q == cell.q && r == cell.r;
        }

        @Override
        public int hashCode() {
            return 31 * q + r;
        }

        @Override
        public String toString() {
            return q + ":" + r;
        }
    }

    private static final class Analysis {

        int synthesisOperations;
        final Map<String, Integer> missingPrimals = new TreeMap<>();
    }

    private static final class CompatibilityGraph {

        final String signature;
        final int registrySize;
        final Aspect[] aspects;
        final Map<Aspect, Integer> aspectIndices;
        final int[][] compatibleIds;
        final boolean[][] compatible;
        final String description;

        private CompatibilityGraph(String signature, int registrySize, Aspect[] aspects,
            Map<Aspect, Integer> aspectIndices, int[][] compatibleIds, boolean[][] compatible, String description) {
            this.signature = signature;
            this.registrySize = registrySize;
            this.aspects = aspects;
            this.aspectIndices = aspectIndices;
            this.compatibleIds = compatibleIds;
            this.compatible = compatible;
            this.description = description;
        }
    }

    private static final class BoardGeometry {

        final Cell[] cells;
        final Map<Cell, Integer> indices;
        final Map<String, Cell> cellsByKey;
        final int[][] neighbors;

        private BoardGeometry(Cell[] cells, Map<Cell, Integer> indices, Map<String, Cell> cellsByKey,
            int[][] neighbors) {
            this.cells = cells;
            this.indices = indices;
            this.cellsByKey = cellsByKey;
            this.neighbors = neighbors;
        }
    }

    private static final class Metrics {

        long expandedStates;
        int routeSearches;
        int peakStates;
        int peakQueue;
        int peakPlans;
        int peakStateCapacity;
        boolean graphCacheHit;
        boolean geometryCacheHit;

        void observe(int states, int queue, int stateCapacity) {
            peakStates = Math.max(peakStates, states);
            peakQueue = Math.max(peakQueue, queue);
            peakStateCapacity = Math.max(peakStateCapacity, stateCapacity);
        }

        long estimatedWorkingBytes() {
            return peakStateCapacity * 12L + peakQueue * 12L + peakPlans * 512L;
        }
    }

    private static final class SteinerWorkspace {

        static final byte LEAF = 1;
        static final byte MERGE = 2;
        static final byte PATH = 3;

        private SteinerWorkspace() {}
    }

    public static final class Result {

        static final Comparator<Result> ORDER = Comparator.comparingInt((Result result) -> result.missingPrimalCount())
            .thenComparingLong(result -> result.totalCost)
            .thenComparingInt(result -> result.placements.size())
            .thenComparingInt(result -> result.synthesisOperations)
            .thenComparing(result -> result.canonical());

        public final boolean success;
        public final Map<String, Aspect> placements;
        public final long totalCost;
        public final int synthesisOperations;
        public final Map<String, Integer> missingPrimals;
        public final Config.SolveMode mode;
        public final boolean timedOut;
        public final boolean fallbackUsed;
        public final String failureReason;
        public final String repairCell;
        public final long solveTimeMs;
        public final long expandedStates;
        public final int routeSearches;
        public final int peakStates;
        public final int peakQueue;
        public final int peakPlans;
        public final long estimatedWorkingBytes;
        public final boolean resultCacheHit;
        public final boolean graphCacheHit;

        private Result(boolean success, Map<String, Aspect> placements, long totalCost, int synthesisOperations,
            Map<String, Integer> missingPrimals, Config.SolveMode mode, boolean timedOut, boolean fallbackUsed,
            String failureReason) {
            this(
                success,
                placements,
                totalCost,
                synthesisOperations,
                missingPrimals,
                mode,
                timedOut,
                fallbackUsed,
                failureReason,
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                false);
        }

        private Result(boolean success, Map<String, Aspect> placements, long totalCost, int synthesisOperations,
            Map<String, Integer> missingPrimals, Config.SolveMode mode, boolean timedOut, boolean fallbackUsed,
            String failureReason, String repairCell, long solveTimeMs, long expandedStates, int routeSearches,
            int peakStates, int peakQueue, int peakPlans, long estimatedWorkingBytes, boolean resultCacheHit,
            boolean graphCacheHit) {
            this.success = success;
            this.placements = Collections.unmodifiableMap(new LinkedHashMap<>(placements));
            this.totalCost = totalCost;
            this.synthesisOperations = synthesisOperations;
            this.missingPrimals = Collections.unmodifiableMap(new TreeMap<>(missingPrimals));
            this.mode = mode;
            this.timedOut = timedOut;
            this.fallbackUsed = fallbackUsed;
            this.failureReason = failureReason;
            this.repairCell = repairCell;
            this.solveTimeMs = solveTimeMs;
            this.expandedStates = expandedStates;
            this.routeSearches = routeSearches;
            this.peakStates = peakStates;
            this.peakQueue = peakQueue;
            this.peakPlans = peakPlans;
            this.estimatedWorkingBytes = estimatedWorkingBytes;
            this.resultCacheHit = resultCacheHit;
            this.graphCacheHit = graphCacheHit;
        }

        static Result success(Map<String, Aspect> placements, long cost, int synthesisOperations,
            Map<String, Integer> missingPrimals, Config.SolveMode mode, boolean timedOut) {
            return new Result(true, placements, cost, synthesisOperations, missingPrimals, mode, timedOut, false, null);
        }

        static Result failure(String reason, boolean timedOut) {
            return failure(reason, timedOut, null);
        }

        static Result failure(String reason, boolean timedOut, String repairCell) {
            return new Result(
                false,
                Collections.emptyMap(),
                0,
                0,
                Collections.emptyMap(),
                null,
                timedOut,
                false,
                reason,
                repairCell,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                false);
        }

        Result withFallback(boolean fallback) {
            return new Result(
                success,
                placements,
                totalCost,
                synthesisOperations,
                missingPrimals,
                mode,
                timedOut,
                fallback,
                failureReason,
                repairCell,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                false);
        }

        Result withDiagnostics(long elapsedMs, Metrics metrics, boolean cacheHit) {
            return new Result(
                success,
                placements,
                totalCost,
                synthesisOperations,
                missingPrimals,
                mode,
                timedOut,
                fallbackUsed,
                failureReason,
                repairCell,
                elapsedMs,
                metrics.expandedStates,
                metrics.routeSearches,
                metrics.peakStates,
                metrics.peakQueue,
                metrics.peakPlans,
                metrics.estimatedWorkingBytes(),
                cacheHit,
                metrics.graphCacheHit);
        }

        public int missingPrimalCount() {
            int total = 0;
            for (Integer value : missingPrimals.values()) total += value;
            return total;
        }

        private String canonical() {
            StringBuilder result = new StringBuilder();
            for (Map.Entry<String, Aspect> entry : placements.entrySet()) {
                result.append(entry.getKey())
                    .append('=')
                    .append(
                        entry.getValue()
                            .getTag())
                    .append(';');
            }
            return result.toString();
        }
    }

    public static final class RepairPlan {

        public final List<String> repairCells;
        public final Result result;

        private RepairPlan(List<String> repairCells, Result result) {
            this.repairCells = Collections.unmodifiableList(new ArrayList<>(repairCells));
            this.result = result;
        }
    }
}
