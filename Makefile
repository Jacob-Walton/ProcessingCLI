GRADLE = sh ./gradlew $(if $(VERSION),-PcliVersion=$(VERSION)) $(if $(JDK_SOURCES),-PjdkSources=$(JDK_SOURCES))
PREFIX ?= $(HOME)/.local

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
	cp -a build/app/ProcessingCLI $(PREFIX)/lib/processingcli
	ln -sf $(PREFIX)/lib/processingcli/bin/ProcessingCLI $(PREFIX)/bin/ProcessingCLI

uninstall:
	rm -rf $(PREFIX)/lib/processingcli $(PREFIX)/bin/ProcessingCLI
