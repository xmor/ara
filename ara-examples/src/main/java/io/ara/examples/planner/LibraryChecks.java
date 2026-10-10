package io.ara.examples.planner;

import java.util.Map;

/**
 * What the run really left in the workspace, checked by code and not taken from the model's word.
 *
 * <p>The strategy reporting success means it got to the end of its plan, not that the task is done:
 * a model can close every step as done and still have written nothing. These checks look at the files.
 * Printing only: nothing here is part of how the strategy is used.
 */
final class LibraryChecks {

    private LibraryChecks() { }

    /** Prints one line per expectation, then the files themselves. */
    static void report(Workspace workspace) {
        PlannerConsole.section("checks on the workspace (the strategy's success is not the task's success)");
        Map<String, String> files = workspace.files();
        String book = pathEndingWith(files, "Book.java");
        String member = pathEndingWith(files, "Member.java");
        String service = pathEndingWith(files, "LoanService.java");

        PlannerConsole.check("Book.java exists", book != null);
        PlannerConsole.check("Member.java exists", member != null);
        PlannerConsole.check("LoanService.java exists", service != null);
        PlannerConsole.check("README.md exists", workspace.read("README.md").isPresent());
        if (book != null && service != null) {
            String bookPackage = packageOf(files.get(book));
            PlannerConsole.check("LoanService is in the same package as Book (" + bookPackage + ")",
                    bookPackage.equals(packageOf(files.get(service))));
        }

        PlannerConsole.section("workspace");
        files.forEach((path, content) -> System.out.println("--- " + path + "\n" + content.stripTrailing()));
    }

    private static String pathEndingWith(Map<String, String> files, String name) {
        return files.keySet().stream().filter(path -> path.endsWith(name)).findFirst().orElse(null);
    }

    private static String packageOf(String source) {
        for (String line : source.split("\n")) {
            if (line.startsWith("package ")) return line.substring(8).replace(";", "").trim();
        }
        return "(default)";
    }
}
