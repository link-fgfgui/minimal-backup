package io.github.linkfgfgui.minimalbackup.client;

import net.minecraft.resources.ResourceLocation;

/** The few client-side Minecraft APIs whose names differ between 1.20.1 and 1.21.1. */
final class ClientCompat {
    private ClientCompat() {
    }

    static ResourceLocation id(String namespace, String path) {
        //? if 1.20.1 {
        /*return new ResourceLocation(namespace, path);
        *///?} else {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
        //?}
    }
}
