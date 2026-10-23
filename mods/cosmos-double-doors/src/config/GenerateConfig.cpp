// Host tool: writes the default config.json and config.schema.json that are
// packaged inside the .levipack.
#include "DoubleDoorsConfig.hpp"

#include <filesystem>
#include <iostream>

int main(int argc, char **argv) {
  if (argc != 2) {
    std::cerr << "usage: cdd_config_gen <mod-package-dir>\n";
    return 2;
  }

  const auto configDir = std::filesystem::path(argv[1]) / "config";
  pl::config::ConfigFile<cdd::DoubleDoorsConfig> config(
      cdd::DoubleDoorsConfig{}, configDir / "config.json",
      configDir / "config.schema.json");

  if (!config.save() || !config.writeSchema()) {
    std::cerr << "failed to write config files into " << configDir << '\n';
    return 1;
  }
  std::cout << "generated " << config.configPath() << '\n'
            << "generated " << config.schemaPath() << '\n';
  return 0;
}
