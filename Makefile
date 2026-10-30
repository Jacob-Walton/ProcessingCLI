GRADLE = sh ./gradlew $(if $(VERSION),-PcliVersion=$(VERSION)) $(if $(JDK_SOURCES),-PjdkSources=$(JDK_SOURCES))
PREFIX ?= $(HOME)/.local
# The app's folder and its launcher in it, a .app bundle on macOS
ifeq ($(shell uname),Darwin)
APP = ProcessingCLI.app
LAUNCHER = Contents/MacOS/ProcessingCLI
else
APP = ProcessingCLI
LAUNCHER = bin/ProcessingCLI
endif

.PHONY: run jar app release clean install uninstall

run:
	$(GRADLE) run --args="$(ARGS)"

jar:
	$(GRADLE) fatJar

app:
	$(GRADLE) packageApp

release:
	$(GRADLE) release

clean:
	$(GRADLE) clean

install: app
	rm -rf $(PREFIX)/lib/processingcli
	mkdir -p $(PREFIX)/lib $(PREFIX)/bin
	cp -a build/app/$(APP) $(PREFIX)/lib/processingcli
	ln -sf $(PREFIX)/lib/processingcli/$(LAUNCHER) $(PREFIX)/bin/ProcessingCLI

uninstall:
	rm -rf $(PREFIX)/lib/processingcli $(PREFIX)/bin/ProcessingCLI
