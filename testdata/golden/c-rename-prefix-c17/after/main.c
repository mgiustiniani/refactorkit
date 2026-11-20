int compute(int value) { return value * 2; }
int read_item(int value) { return value + 1; }
int calculate_suffix(void) { return 9; }
/* calculate and load_item are documentation, not references. */
const char *api_names(void) { return "calculate load_item"; }
int main(void) { return compute(21) + read_item(1) == 44 ? 0 : 1; }
