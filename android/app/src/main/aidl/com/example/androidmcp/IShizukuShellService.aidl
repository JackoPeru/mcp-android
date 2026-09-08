package com.example.androidmcp;

interface IShizukuShellService {
    String execute(String script, String stdin, String workdir, int timeoutMs) = 1;
    void destroy() = 16777114;
}
