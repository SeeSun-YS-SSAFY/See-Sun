'use client';

import { useState, useId } from "react";
import Icon from "./Icon";

interface CheckboxProps {
  checked?: boolean;
  onChange?: (checked: boolean) => void;
  label?: string;
}

export default function Checkbox({ checked: propChecked, onChange, label = "Label" }: CheckboxProps) {
  const [internalChecked, setInternalChecked] = useState(false);
  const checked = propChecked !== undefined ? propChecked : internalChecked;
  const id = useId();

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const newChecked = e.target.checked;
    if (onChange) {
      onChange(newChecked);
    } else {
      setInternalChecked(newChecked);
    }
  };

  const handleClick = () => {
    const newChecked = !checked;
    if (onChange) {
      onChange(newChecked);
    } else {
      setInternalChecked(newChecked);
    }
  };

  return (
    <div className="rounded-2xl inline-flex justify-center items-center gap-2.5 overflow-hidden">
      <input
        type="checkbox"
        hidden
        id={id}
        checked={checked}
        onChange={handleChange}
      />
      <div
        className="w-16 h-16 rounded-2xl outline-[5px] outline-offset-[-5px] outline-white flex justify-center items-center gap-2.5 overflow-hidden cursor-pointer"
        onClick={handleClick}
      >
        {checked && <Icon name="check" filled size={60} color="white" variation='rounded' weight={500} />}
      </div>
      <label htmlFor={id} className="cursor-pointer justify-start text-white text-body-large font-extrabold font-['KoddiUD_OnGothic']">
        {label}
      </label>
    </div>
  );
}