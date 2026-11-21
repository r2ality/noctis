{
  description = "Noctis - lightweight, privacy-first Android browser development shell";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in {
      devShells = forAllSystems (system:
        let
          # Explicitly accept the Android SDK license terms for NixOS builds.
          pkgs = import nixpkgs {
            inherit system;
            config = {
              # Android SDK packages are marked unfree by Nixpkgs. This only
              # applies to this flake's development package set; it does not
              # change the user's system-wide NixOS configuration.
              allowUnfree = true;

              # Explicitly accept the Android SDK license for this build shell.
              android_sdk.accept_license = true;
            };
          };
          buildToolsVersion = "36.0.0";
          androidSdk = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "36" ];
            buildToolsVersions = [ buildToolsVersion ];
            includeNDK = false;
            includeEmulator = false;
            includeSources = false;
            includeSystemImages = false;
          };
        in {
          default = pkgs.mkShell {
            packages = with pkgs; [
              jdk17
              gradle
              android-tools
              androidSdk.androidsdk
              curl
              unzip
              coreutils
            ];

            ANDROID_HOME = "${androidSdk.androidsdk}/libexec/android-sdk";
            ANDROID_SDK_ROOT = "${androidSdk.androidsdk}/libexec/android-sdk";
            JAVA_HOME = "${pkgs.jdk17}";

            # On NixOS, the AAPT2 executable downloaded from Maven by AGP may
            # not start because it expects a conventional FHS runtime layout.
            # Use the AAPT2 binary shipped in the Nix-built Android SDK instead.
            # Keep this version in sync with buildToolsVersions above.
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk.androidsdk}/libexec/android-sdk/build-tools/${buildToolsVersion}/aapt2";

            shellHook = ''
              echo "Noctis Android development environment"
              echo "Android SDK unfree packages allowed for this development shell."
              echo "Android SDK license accepted through config.android_sdk.accept_license."
              echo "Using Nix-provided AAPT2 from Android Build Tools ${buildToolsVersion}."
              echo "Build with: ./gradlew assembleDebug"
            '';
          };
        });
    };
}
