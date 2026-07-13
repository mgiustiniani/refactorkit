export const registry = {};

export function register(name, value) {
  registry[name] = value;
}
