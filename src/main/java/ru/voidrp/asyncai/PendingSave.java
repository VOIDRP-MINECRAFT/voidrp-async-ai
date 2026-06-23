package ru.voidrp.asyncai;

import java.nio.file.Path;

public record PendingSave(byte[] bytes, Path tempPath) {}
