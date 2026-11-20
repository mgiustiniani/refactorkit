static int old(int x);
static int old(int x) { return x + 1; }
static int oldish(int x) { return x + 10; }
int main(void) {
  const char *message = "old oldish";
  // old remains a comment
  return old(1) + oldish(1) + (message[0] == 'o' ? 0 : 1);
}
