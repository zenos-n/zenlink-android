{
  description = "ZenLink Android development environment";
  inputs.nixpkgs.url = "https://channels.nixos.org/nixos-26.05/nixexprs.tar.zst";
  outputs = { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = { allowUnfree = true; android_sdk.accept_license = true; };
      };
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "37.0" ];
        buildToolsVersions = [ "37.0.0" ];
        includeEmulator = false;
        includeNDK = false;
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = [ pkgs.git pkgs.jdk21 pkgs.android-tools android.androidsdk ];
        JAVA_HOME = pkgs.jdk21.home;
        ANDROID_HOME = sdk;
        ANDROID_SDK_ROOT = sdk;
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/37.0.0/aapt2";
      };
    };
}
