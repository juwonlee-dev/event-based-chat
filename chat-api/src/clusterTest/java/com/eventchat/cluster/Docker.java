package com.eventchat.cluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** compose로 띄운 컨테이너를 docker CLI로 다룬다(강제 종료, 일시 정지, 로그). */
final class Docker {

    private Docker() {
    }

    static String run(String... args) {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(Arrays.asList(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(90, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException(String.join(" ", command) + " 실패: " + output);
            }
            return output.strip();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 앱 인스턴스: 컨테이너 IP → 이름(nginx가 붙이는 X-Upstream이 IP:포트다). */
    static Map<String, String> appContainers(String project) {
        Map<String, String> byIp = new LinkedHashMap<>();
        String names = run("ps", "--filter", "label=com.docker.compose.project=" + project,
                "--filter", "label=com.docker.compose.service=app", "--format", "{{.Names}}");
        for (String name : names.split("\s+")) {
            if (!name.isBlank()) {
                byIp.put(run("inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", name), name);
            }
        }
        return byIp;
    }
}
