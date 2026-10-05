import { Checkbox, PasswordInput, Select, Switch, TagsInput, Textarea, TextInput } from '@mantine/core';

interface FieldProps<T> {
  label?: string;
  value: T;
  onChange: (value: T) => void;
  error?: string | null;
  description?: string;
  disabled?: boolean;
  required?: boolean;
}

export function TextField({ label, value, onChange, error, description, disabled, required, placeholder,
  type = 'text', autoComplete, autoFocus }: FieldProps<string> & {
  placeholder?: string;
  type?: 'text' | 'email' | 'search';
  autoComplete?: string;
  autoFocus?: boolean;
}) {
  return (
    <TextInput label={label} value={value} onChange={(e) => onChange(e.currentTarget.value)} error={error}
      description={description} disabled={disabled} required={required} placeholder={placeholder} type={type}
      autoComplete={autoComplete} autoFocus={autoFocus} />
  );
}

export function TextAreaField({ label, value, onChange, error, description, disabled }: FieldProps<string>) {
  return (
    <Textarea label={label} value={value} onChange={(e) => onChange(e.currentTarget.value)} error={error}
      description={description} disabled={disabled} autosize minRows={2} />
  );
}

export function PasswordField({ label, value, onChange, error, description, disabled, required, autoComplete }:
  FieldProps<string> & { autoComplete?: string }) {
  return (
    <PasswordInput label={label} value={value} onChange={(e) => onChange(e.currentTarget.value)} error={error}
      description={description} disabled={disabled} required={required} autoComplete={autoComplete} />
  );
}

export interface Option {
  value: string;
  label: string;
}

/** Выбор одного значения; null — ничего не выбрано. */
export function SelectField({ label, value, onChange, options, placeholder, clearable, error, disabled }:
  FieldProps<string | null> & { options: Option[]; placeholder?: string; clearable?: boolean }) {
  return (
    <Select label={label} value={value} onChange={onChange} data={options} placeholder={placeholder}
      clearable={clearable} error={error} disabled={disabled} allowDeselect={false} comboboxProps={{ withinPortal: true }} />
  );
}

/** Список тегов: ввод с Enter или запятой, подсказки из suggestions. */
export function TagsField({ label, value, onChange, suggestions, placeholder, error, description, maxTags }:
  FieldProps<string[]> & { suggestions?: string[]; placeholder?: string; maxTags?: number }) {
  return (
    <TagsInput label={label} value={value} onChange={onChange} data={suggestions} placeholder={placeholder}
      error={error} description={description} maxTags={maxTags} splitChars={[',']} clearable />
  );
}

export function CheckboxField({ label, value, onChange, disabled }: FieldProps<boolean> & { label: string }) {
  return <Checkbox label={label} checked={value} onChange={(e) => onChange(e.currentTarget.checked)} disabled={disabled} />;
}

export function SwitchField({ label, value, onChange, disabled }: FieldProps<boolean> & { label: string }) {
  return <Switch label={label} checked={value} onChange={(e) => onChange(e.currentTarget.checked)} disabled={disabled} />;
}
