package dev.alastorkaneki.launcherbrowser;

interface IPrivilegedShell {
    String[] execute(String command) = 1;
    void destroy() = 16777114;
}
