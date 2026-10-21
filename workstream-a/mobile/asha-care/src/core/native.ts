// Typed access to the app's native module, plus the ID helpers the visit code needs.
import Native from '../native/NativeAshaSecure';
import type {Ids} from './visit';

export const native = Native;

export const ids: Ids = {
  randomId: (prefix: string) => `${prefix}_${Native.randomHex(16)}`,
  uuid: () => Native.uuid(),
};

export const settings = Native.getConstants();
