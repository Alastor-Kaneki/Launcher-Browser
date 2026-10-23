#pragma once

#include <cstdio>
#include <functional>
#include <string>
#include <vector>

namespace cdd_test {

struct TestCase {
  const char *name;
  std::function<void()> body;
};

inline std::vector<TestCase> &registry() {
  static std::vector<TestCase> cases;
  return cases;
}

inline int &failures() {
  static int count = 0;
  return count;
}

struct Registrar {
  Registrar(const char *name, std::function<void()> body) {
    registry().push_back({name, std::move(body)});
  }
};

inline int runAll() {
  int failedCases = 0;
  for (const auto &test : registry()) {
    const int before = failures();
    test.body();
    const bool ok = failures() == before;
    if (!ok) ++failedCases;
    std::printf("[%s] %s\n", ok ? " OK " : "FAIL", test.name);
  }
  std::printf("%zu tests, %d failed\n", registry().size(), failedCases);
  return failedCases == 0 ? 0 : 1;
}

} // namespace cdd_test

#define CDD_CONCAT2(a, b) a##b
#define CDD_CONCAT(a, b) CDD_CONCAT2(a, b)
#define TEST(name)                                                             \
  static void CDD_CONCAT(test_, name)();                                       \
  static ::cdd_test::Registrar CDD_CONCAT(registrar_, name)(                   \
      #name, &CDD_CONCAT(test_, name));                                        \
  static void CDD_CONCAT(test_, name)()

#define CHECK(expr)                                                            \
  do {                                                                         \
    if (!(expr)) {                                                             \
      std::printf("  %s:%d: CHECK(%s) failed\n", __FILE__, __LINE__, #expr);   \
      ++::cdd_test::failures();                                                \
    }                                                                          \
  } while (0)

#define REQUIRE(expr)                                                          \
  do {                                                                         \
    if (!(expr)) {                                                             \
      std::printf("  %s:%d: REQUIRE(%s) failed\n", __FILE__, __LINE__, #expr); \
      ++::cdd_test::failures();                                                \
      return;                                                                  \
    }                                                                          \
  } while (0)
