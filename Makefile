# With only Command Line Tools (no Xcode), Swift Testing needs its framework dir on the search path and rpath.
CLT_FW := /Library/Developer/CommandLineTools/Library/Developer/Frameworks
SWIFT_TEST_FLAGS := $(if $(findstring CommandLineTools,$(shell xcode-select -p 2>/dev/null)),-Xswiftc -F -Xswiftc $(CLT_FW) -Xlinker -rpath -Xlinker $(CLT_FW),)

.PHONY: mac mac-test run-mac android android-test install-android test fixtures-check

mac:
	cd apps/macos && swift build -c release && scripts/bundle.sh

mac-test:
	cd apps/macos && swift test $(SWIFT_TEST_FLAGS)

run-mac:
	open apps/macos/build/Kopi.app

android:
	cd apps/android && ./gradlew :app:assembleDebug

android-test:
	cd apps/android && ./gradlew test

install-android:
	adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk

test: fixtures-check mac-test android-test

fixtures-check:
	@for f in protocol/fixtures/*.json; do python3 -m json.tool "$$f" >/dev/null || exit 1; done
