package com.openscad.standalone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.Layout;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.EditText;

class LineNumberEditText extends EditText {

    private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gutterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int gutterWidth;
    private int extraLeftPadding;

    LineNumberEditText(Context context) {
        super(context);
        init();
    }

    LineNumberEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    LineNumberEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        numberPaint.setColor(Color.parseColor("#7f95b4"));
        numberPaint.setTextAlign(Paint.Align.RIGHT);
        numberPaint.setTypeface(getTypeface());

        dividerPaint.setColor(Color.parseColor("#30445f"));
        dividerPaint.setStrokeWidth(dp(1));

        gutterPaint.setColor(Color.parseColor("#0f1724"));

        addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateGutterWidth();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        updateGutterWidth();
    }

    @Override
    public void setTextSize(float size) {
        super.setTextSize(size);
        syncNumberPaint();
        updateGutterWidth();
    }

    @Override
    public void setTextSize(int unit, float size) {
        super.setTextSize(unit, size);
        syncNumberPaint();
        updateGutterWidth();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateGutterWidth();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        if (layout != null) {
            int scrollX = getScrollX();
            int scrollY = getScrollY();

            float gutterLeft = scrollX;
            float gutterRight = scrollX + gutterWidth;
            canvas.drawRect(gutterLeft, scrollY, gutterRight, scrollY + getHeight(), gutterPaint);
            canvas.drawLine(gutterRight, scrollY, gutterRight, scrollY + getHeight(), dividerPaint);

            int firstLine = layout.getLineForVertical(scrollY);
            int lastLine = layout.getLineForVertical(scrollY + getHeight());
            float x = gutterRight - dp(6);

            for (int line = firstLine; line <= lastLine; line++) {
                int baseline = layout.getLineBaseline(line) + getTotalPaddingTop();
                canvas.drawText(Integer.toString(line + 1), x, baseline, numberPaint);
            }
        }

        super.onDraw(canvas);
    }

    private void syncNumberPaint() {
        numberPaint.setTextSize(getTextSize() * 0.78f);
        numberPaint.setTypeface(getTypeface());
    }

    private void updateGutterWidth() {
        syncNumberPaint();

        int lineCount = Math.max(1, getLineCount());
        int digits = Integer.toString(lineCount).length();
        StringBuilder maxText = new StringBuilder();
        for (int i = 0; i < digits; i++) {
            maxText.append('9');
        }

        gutterWidth = (int) (numberPaint.measureText(maxText.toString()) + dp(14));
        int targetPadding = gutterWidth + dp(8);
        if (targetPadding != extraLeftPadding) {
            extraLeftPadding = targetPadding;
            setPadding(extraLeftPadding, getPaddingTop(), getPaddingRight(), getPaddingBottom());
        }
        invalidate();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            getResources().getDisplayMetrics()));
    }
}
