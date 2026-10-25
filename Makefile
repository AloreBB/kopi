# With only Command Line Tools (no Xcode), Swift Testing needs its framework dir on the search path and rpath.
CLT_FW := /Library/Developer/CommandLineTools/Library/Developer/Frameworks
SWIFT_TEST_FLAGS := $(if $(findstring CommandLineTools,$(shell xcode-select -p)),-Xswiftc -F -Xswiftc $(CLT_FW) -Xlinker -rpath -Xlinker $(CLT_FW),)

.PHONY: mac mac-test run-mac android android-test install-android test fixtures-check

mac:
	@echo "not yet" && exit 1

mac-test:
	cd apps/macos && swift test $(SWIFT_TEST_FLAGS)

run-mac:
	@echo "not yet" && exit 1

android:
	@echo "not yet" && exit 1

android-test:
	@echo "not yet" && exit 1

install-android:
	@echo "not yet" && exit 1

test:
	@echo "not yet" && exit 1

fixtures-check:
	@for f in protocol/fixtures/*.json; do python3 -m json.tool "$$f" >/dev/null || exit 1; done
