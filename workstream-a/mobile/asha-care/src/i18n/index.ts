// Interface strings shared with the web app (generated from its i18n.js by `npm run strings`).
// A string without a translation is shown in English, never machine-translated here.
import strings from './strings.json';

export const LANGUAGES = strings.languages as Array<[string, string, string]>;
const TRANSLATIONS = strings.translations as Record<string, Record<string, string>>;

export function t(lang: string, text: string): string {
  if (lang === 'en') return text;
  return (TRANSLATIONS[lang] || {})[text] || text;
}

export function languageTag(lang: string): string {
  return (LANGUAGES.find(([code]) => code === lang) || LANGUAGES[0])[1];
}
