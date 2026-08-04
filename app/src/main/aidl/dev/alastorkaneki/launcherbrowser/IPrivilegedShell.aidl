package dev.alastorkaneki.launcherbrowser;

interface IPrivilegedShell {
    String[] execute(String command);
    void destroy() = 16777114;
}
