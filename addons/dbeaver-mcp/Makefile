.PHONY: overlay build test validate e2e-databases runtime-smoke policy-smoke release package-linux clean-overlay compat status

overlay:
	./scripts/overlay.sh

build:
	./scripts/build.sh

test:
	./scripts/test.sh

validate:
	./scripts/validate.sh

e2e-databases:
	./scripts/e2e-databases.sh

runtime-smoke:
	./scripts/runtime-smoke.sh

policy-smoke:
	./scripts/policy-smoke.sh

release:
	./scripts/release.sh

package-linux:
	./scripts/package-linux.sh

clean-overlay:
	./scripts/clean-overlay.sh

compat:
	./scripts/compat-report.sh

status:
	git status --short
