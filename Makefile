.PHONY: mac mac-test run-mac android android-test install-android test fixtures-check

mac:
	@echo "not yet" && exit 1

mac-test:
	@echo "not yet" && exit 1

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
