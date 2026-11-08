package main

// Native helpers for Discord EVS Generator.
// Build with gomobile (see README.md) → libevs_core.so for Android ABIs.
//
// This is NOT unbreakable. It raises the cost of casual jadx decompile of
// YOUR logic. Framework packages android/ and androidx/ will always appear.

/*
#include <stdlib.h>
#include <string.h>
*/
import "C"

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"strings"
	"unsafe"
)

//export Java_com_discord_evs_generator_security_NativeBridge_nativeProbe
func Java_com_discord_evs_generator_security_NativeBridge_nativeProbe(env unsafe.Pointer, clazz unsafe.Pointer) C.int {
	return 1
}

//export Java_com_discord_evs_generator_security_NativeBridge_nativeHmacSha256
func Java_com_discord_evs_generator_security_NativeBridge_nativeHmacSha256(
	env unsafe.Pointer, clazz unsafe.Pointer,
	key jstring, message jstring,
) jstring {
	// Real JNI string handling requires the full JNIEnv API.
	// Prefer building via gomobile bind (see README) which generates this glue.
	return 0
}

//export Java_com_discord_evs_generator_security_NativeBridge_nativeIntegrityHint
func Java_com_discord_evs_generator_security_NativeBridge_nativeIntegrityHint(
	env unsafe.Pointer, clazz unsafe.Pointer,
	packageName jstring, sigSha256Hex jstring,
) C.int {
	return 1
}

// Pure Go helpers used when building with gomobile bind -app or aar.
func HmacHex(key, message string) string {
	mac := hmac.New(sha256.New, []byte(key))
	mac.Write([]byte(message))
	return hex.EncodeToString(mac.Sum(nil))
}

func IntegrityHint(packageName, sig string) bool {
	if !strings.EqualFold(packageName, "com.discord.evs.generator") {
		return false
	}
	if sig == "" {
		return true
	}
	return len(sig) >= 32
}

// Placeholder JNI types so file type-checks without full cgo JNI headers in docs builds.
type jstring uintptr

func main() {}
