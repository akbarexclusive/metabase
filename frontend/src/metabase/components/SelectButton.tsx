import React, { forwardRef, HTMLAttributes } from "react";
import cx from "classnames";
import Icon from "metabase/components/Icon";

type Props = HTMLAttributes<HTMLDivElement> & {
  hasValue?: boolean;
  children: React.ReactNode;
  left?: React.ReactNode;
};

const SelectButton = forwardRef<HTMLDivElement, Props>(function SelectButton(
  { className, children, left, hasValue, ...props }: Props,
  ref,
) {
  return (
    <div
      {...props}
      className={cx(className, "AdminSelect flex align-center", {
        "text-medium": !hasValue,
      })}
      ref={ref}
    >
      {React.isValidElement(left) && left}
      <span className="AdminSelect-content mr1">{children}</span>
      <Icon
        className="AdminSelect-chevron flex-align-right"
        name="chevrondown"
        size={12}
      />
    </div>
  );
});

export default SelectButton;