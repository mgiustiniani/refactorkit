static int value = 4;
static int calculate(int value) {
  int result = value;
  { int value = 3; result += value; }
  return result + value;
}
static int read(void) { return value; }
int main(void) { int value = 1; const char *label = "value"; return calculate(value) + read() + (label[0] == 'v' ? 0 : 1); }
// value remains a comment
